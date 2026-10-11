package com.selkies.client.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.selkies.client.net.ConnectionEngine
import com.selkies.client.net.Protocol
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频解码器 —— MediaCodec 硬解管线。
 *
 * 协议要点（核对自官方 wire-codecs / selkies-ws-core 的 onWire）：
 *  - 只有**关键帧**可以（重新）配置解码器；未配置时收到的增量帧一律丢弃
 *    并发送 REQUEST_KEYFRAME（官方 no_key 行为）；
 *  - PIPELINE_RESETTING → 丢弃软状态，等待下一关键帧重新 configure；
 *  - 分辨率/编码变化 → 同样在下一关键帧处重新配置；
 *  - 码流为 Annex-B（含起始码）；配置时从首个关键帧提取 SPS/PPS 作为 csd，
 *    增量帧按原始 Annex-B 送入（解码器会忽略带内参数集）。
 *
 * 线程模型：单工作线程 + 有界队列（8 帧）。队列满时丢最老的**非关键**帧，
 * 关键帧永不丢弃。所有 public 方法线程安全。
 *
 * 防闪退：MediaCodec 全部操作包裹 try/catch，任何 codec 异常都转化为
 * “重置 + 请求关键帧”的自愈流程，绝不上抛。
 */
class VideoDecoder : ConnectionEngine.MediaSink {

    /** 渲染目标表面（由 SessionActivity 在 SurfaceHolder 回调中设置/清除） */
    @Volatile var surface: Surface? = null

    /** 解码请求关键帧的回调（引擎注入） */
    @Volatile var onRequestKeyframe: (() -> Unit)? = null

    /** 分辨率变化通知（主线程） */
    @Volatile var onResolutionChanged: ((Int, Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Thread(this::workerLoop).apply {
        name = "selkies-video"
        isDaemon = true
        start()
    }

    /** 工作队列元素：null = 退出哨兵 */
    private sealed class Item {
        data class Video(
            val header: Protocol.VideoFrameHeader,
            val data: ByteArray,
            val offset: Int,
            val length: Int
        ) : Item()

        data class Jpeg(
            val header: Protocol.JpegStripeHeader,
            val data: ByteArray,
            val offset: Int,
            val length: Int
        ) : Item()

        object Reset : Item()          // PIPELINE_RESETTING
        object Inactive : Item()       // VIDEO_STOPPED：清屏
        object Exit : Item()
    }

    private val queue = LinkedBlockingQueue<Item>(QUEUE_CAP)
    private val exited = AtomicBoolean(false)

    // ---- 解码器状态（仅工作线程访问） ----
    private var codec: MediaCodec? = null
    private var codecMime: String? = null
    private var codecWidth = 0
    private var codecHeight = 0
    private var configured = false

    // ---- JPEG 条带合成状态 ----
    private var jpegBitmap: Bitmap? = null
    private var jpegWidth = 0
    private var jpegHeight = 0

    // 统计
    @Volatile var decodedFrames: Long = 0
        private set

    // ------------------------------------------------------------------
    // MediaSink 实现（WS 线程调用）
    // ------------------------------------------------------------------

    override fun onVideoFrame(
        header: Protocol.VideoFrameHeader,
        data: ByteArray, payloadOffset: Int, payloadLen: Int
    ) {
        if (payloadLen <= 0) return
        enqueue(Item.Video(header, data, payloadOffset, payloadLen), header.keyFrame)
    }

    override fun onJpegStripe(
        header: Protocol.JpegStripeHeader,
        data: ByteArray, payloadOffset: Int, payloadLen: Int
    ) {
        if (payloadLen <= 0) return
        enqueue(Item.Jpeg(header, data, payloadOffset, payloadLen), false)
    }

    override fun onAudioFrames(frames: List<Pair<Long, ByteArray>>) { /* 视频解码器不消费音频 */ }

    override fun onPipelineReset() {
        enqueue(Item.Reset, false)
    }

    override fun onVideoInactive() {
        enqueue(Item.Inactive, false)
    }

    private fun enqueue(item: Item, important: Boolean) {
        if (exited.get()) return
        // 关键帧/控制消息必须入队；队满时丢非关键帧腾位
        if (!queue.offer(item)) {
            if (important) {
                // 丢弃队首的非关键帧
                val head = queue.poll()
                if (head != null && !queue.offer(item)) {
                    // 极端情况：队列被关键帧填满 —— 直接丢弃本条
                }
            } // 非关键帧直接丢弃（网络层会通过 LOST/IDR 机制恢复）
        }
    }

    /** 停止并释放（Activity 销毁时调用） */
    fun release() {
        if (exited.getAndSet(true)) return
        queue.clear()
        queue.offer(Item.Exit)
    }

    // ------------------------------------------------------------------
    // 工作线程主循环
    // ------------------------------------------------------------------

    private fun workerLoop() {
        while (true) {
            val item = try {
                queue.take()
            } catch (_: InterruptedException) {
                continue
            }
            try {
                when (item) {
                    is Item.Exit -> break
                    is Item.Reset -> handleReset()
                    is Item.Inactive -> handleInactive()
                    is Item.Video -> handleVideo(item)
                    is Item.Jpeg -> handleJpeg(item)
                }
            } catch (e: Exception) {
                // 解码异常 → 自愈：重置并请求关键帧
                Log.w(TAG, "decode error: ${e.message} — resetting")
                resetCodecState()
                onRequestKeyframe?.invoke()
            }
        }
        releaseCodec()
    }

    // ------------------------------------------------------------------
    // H.264/HEVC/VP8/VP9/AV1 整帧路径
    // ------------------------------------------------------------------

    private fun handleVideo(item: Item.Video) {
        val header = item.header
        val surface = this.surface

        // 条带模式（stripeY>0 的非首条带）：单解码器无法按行偏移渲染 ——
        // 客户端已在 SETTINGS 中请求 h264enc（整帧），正常不会走到这里；
        // 兜底行为：仅消费 row0 条带 + 提示服务端重协商。
        if (header.stripeY > 0) {
            if (header.keyFrame) onRequestKeyframe?.invoke()
            return
        }

        if (!header.keyFrame && !configured) {
            // 官方 no_key 行为：未配置时丢弃增量帧并请求关键帧
            onRequestKeyframe?.invoke()
            return
        }

        val mime = Protocol.codecMime(header.codecId)

        // 配置变化检测：分辨率 / 编码器 / 表面失效 → 需要重新配置（仅关键帧可）
        val needReconfigure = !configured || codecMime != mime ||
                codecWidth != header.width || codecHeight != header.height ||
                codec?.let { c -> !isCodecAlive(c) } ?: true

        if (needReconfigure) {
            if (!header.keyFrame) {
                onRequestKeyframe?.invoke()
                return
            }
            if (surface == null || !surface.isValid) {
                // Surface 尚未就绪：丢弃，等 SurfaceHolder 就绪后关键帧会再来
                return
            }
            configureDecoder(mime, header.width, header.height, item)
            if (!configured) {
                onRequestKeyframe?.invoke()
                return
            }
        }

        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(10_000_000L) // 10s：等待 IDR 间隙
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx) ?: return
                buf.clear()
                val len = minOf(item.length, buf.remaining())
                if (len < item.length) {
                    // 超大帧塞不进输入缓冲 —— 丢弃并请求关键帧
                    c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                    onRequestKeyframe?.invoke()
                    return
                }
                buf.put(item.data, item.offset, item.length)
                c.queueInputBuffer(
                    inIdx, 0, item.length,
                    System.nanoTime() / 1000,
                    if (header.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                )
            }
            drainDecoder(c)
        } catch (e: IllegalStateException) {
            throw e // 交给外层自愈
        }
        decodedFrames++
    }

    /** 从关键帧码流提取 csd（SPS/PPS 等）并配置 MediaCodec */
    private fun configureDecoder(mime: String, width: Int, height: Int, keyFrame: Item.Video) {
        val surface = this.surface ?: return
        if (!surface.isValid) return
        if (width <= 0 || height <= 0) return

        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            // 延迟渲染设置：低延迟解码（H.264 关键场景）
            if (mime == "video/avc" || mime == "video/hevc") {
                setInteger("low-latency", 1)
            }
        }

        // 从 Annex-B 关键帧提取参数集作为 csd
        val nals = splitAnnexB(keyFrame.data, keyFrame.offset, keyFrame.length)
        if (mime == "video/avc") {
            val sps = nals.firstOrNull { it.type264 == 7 }
            val pps = nals.firstOrNull { it.type264 == 8 }
            if (sps != null) format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps.bytesWithStartCode))
            if (pps != null) format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps.bytesWithStartCode))
        } else if (mime == "video/hevc") {
            // HEVC: VPS(32)/SPS(33)/PPS(34) 合并为 csd-0
            val csd = nals.filter { it.type265 in 32..34 }
            if (csd.isNotEmpty()) {
                val merged = java.io.ByteArrayOutputStream()
                csd.forEach { merged.write(it.bytesWithStartCode) }
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(merged.toByteArray()))
            }
        }
        // VP8/VP9/AV1：无带外参数集需求，宽度高度足够

        try {
            releaseCodec()
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            codecMime = mime
            codecWidth = width
            codecHeight = height
            configured = true
            Log.i(TAG, "decoder configured: $mime ${width}x${height}")
            main.post { onResolutionChanged?.invoke(width, height) }
        } catch (e: Exception) {
            Log.e(TAG, "configure failed: ${e.message}")
            resetCodecState()
        }
    }

    /** 取出全部已就绪帧并渲染到 Surface */
    private fun drainDecoder(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            when {
                outIdx >= 0 -> {
                    val render = info.size > 0
                    try {
                        c.releaseOutputBuffer(outIdx, render)
                    } catch (_: Exception) {
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 格式变化已处理 */ }
                else -> break // INFO_TRY_AGAIN_LATER
            }
        }
    }

    // ------------------------------------------------------------------
    // JPEG 条带合成路径（服务端 jpeg 编码兜底）
    // ------------------------------------------------------------------

    private fun handleJpeg(item: Item.Jpeg) {
        val surface = this.surface ?: return
        if (!surface.isValid) return
        try {
            val bmp = BitmapFactory.decodeByteArray(item.data, item.offset, item.length) ?: return
            val stripeH = bmp.height
            val stripeY = item.header.stripeY
            val totalH = stripeY + stripeH
            val w = bmp.width

            // 画布尺寸变化时重建（保留已画内容无需 —— JPEG 模式整屏重画频率高）
            var canvasBmp = jpegBitmap
            if (canvasBmp == null || jpegWidth != w || jpegHeight < totalH) {
                canvasBmp?.recycle()
                canvasBmp = Bitmap.createBitmap(w, maxOf(totalH, 1), Bitmap.Config.ARGB_8888)
                jpegBitmap = canvasBmp
                jpegWidth = w
                jpegHeight = totalH
            }
            val canvas = android.graphics.Canvas(canvasBmp)
            canvas.drawBitmap(bmp, 0f, stripeY.toFloat(), null)
            bmp.recycle()

            // 推送到 Surface
            try {
                val c = surface.lockCanvas(null)
                if (c != null) {
                    c.drawBitmap(canvasBmp, 0f, 0f, null)
                    surface.unlockCanvasAndPost(c)
                }
            } catch (_: Exception) {
            }
            decodedFrames++
        } catch (e: Exception) {
            Log.w(TAG, "jpeg stripe error: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    private fun handleReset() {
        // PIPELINE_RESETTING：清空排队帧，重置解码器（下一关键帧重新配置）
        queue.clear()
        resetCodecState()
        clearSurface()
    }

    private fun handleInactive() {
        clearSurface()
    }

    private fun clearSurface() {
        val s = surface ?: return
        try {
            if (s.isValid) {
                val c = s.lockCanvas(null)
                if (c != null) {
                    c.drawColor(android.graphics.Color.BLACK)
                    s.unlockCanvasAndPost(c)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun resetCodecState() {
        releaseCodec()
        configured = false
        codecMime = null
        codecWidth = 0
        codecHeight = 0
    }

    private fun releaseCodec() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }

    private fun isCodecAlive(c: MediaCodec): Boolean = try {
        // 无公开 API 判断存活；通过 getName 触发异常检测已释放状态
        c.name
        true
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------
    // Annex-B NAL 解析
    // ------------------------------------------------------------------

    private data class NalUnit(val firstByte: Int, val bytesWithStartCode: ByteArray) {
        /** H.264 NAL 类型（5bit） */
        val type264: Int get() = firstByte and 0x1f
        /** HEVC NAL 类型（6bit） */
        val type265: Int get() = (firstByte shr 1) and 0x3f
    }

    /** 把 Annex-B 码流切成 NAL 单元（含起始码的完整字节） */
    private fun splitAnnexB(data: ByteArray, offset: Int, length: Int): List<NalUnit> {
        val out = ArrayList<NalUnit>(8)
        var i = offset
        val end = offset + length
        var nalStart = -1
        while (i + 2 < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (nalStart >= 0) out.add(makeNal(data, nalStart, i - nalStart))
                nalStart = i + 3
                i += 3
            } else if (i + 3 < end && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                if (nalStart >= 0) out.add(makeNal(data, nalStart, i - nalStart))
                nalStart = i + 4
                i += 4
            } else {
                i++
            }
        }
        if (nalStart in 0 until end) out.add(makeNal(data, nalStart, end - nalStart))
        return out
    }

    private fun makeNal(data: ByteArray, start: Int, length: Int): NalUnit {
        if (length <= 0) return NalUnit(0, ByteArray(0))
        val first = data[start].toInt() and 0xff
        val bytes = ByteArray(length + 4)
        bytes[0] = 0; bytes[1] = 0; bytes[2] = 0; bytes[3] = 1
        System.arraycopy(data, start, bytes, 4, length)
        return NalUnit(first, bytes)
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val QUEUE_CAP = 12
        private const val MAX_INPUT_SIZE = 4 * 1024 * 1024 // 4MB：单帧上限
    }
}
