package com.cshdotcom.selkies.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 (Annex-B) 硬解码器：MediaCodec → Surface(TextureView)。
 * - 每个 0x04 二进制消息为一个访问单元，直接送入解码队列
 * - 关键帧头携带的宽高用于缩放；INFO_OUTPUT_FORMAT_CHANGED 兜底
 * - 队列满时丢弃非关键帧并回报 LOST_FRAME（回调）
 * - PIPELINE_RESETTING 时 flush（IDR 到达前清空残留）
 */
class VideoDecoder(
    private val surface: Surface,
    private val onLostFrame: (Int) -> Unit,
    private val onFormatChanged: (Int, Int) -> Unit
) {
    companion object { private const val TAG = "VideoDecoder"; private const val QUEUE_CAP = 90 }

    private class Frame(
        val frameId: Int, val key: Boolean, val w: Int, val h: Int,
        val data: ByteArray, val offset: Int
    )

    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<Frame>(QUEUE_CAP)
    private var decW = 0
    private var decH = 0
    private var pendingFlush = false
    @Volatile private var lastQueuedId = -1

    fun start(w: Int, h: Int) {
        stop()
        running.set(true)
        decW = w; decH = h
        try {
            codec = MediaCodec.createDecoderByType("video/avc").also { c ->
                val fmt = MediaFormat.createVideoFormat("video/avc", w.coerceAtLeast(16), h.coerceAtLeast(16))
                c.configure(fmt, surface, null, 0)
                c.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建 H264 解码器失败", e)
            running.set(false)
            return
        }
        thread = Thread(this::loop, "selkies-video-decode").apply { isDaemon = true; start() }
    }

    /** 收到一帧：队列满时丢最旧的非关键帧 */
    fun onFrame(frameId: Int, key: Boolean, w: Int, h: Int, data: ByteArray, headerLen: Int) {
        if (!running.get()) {
            // 首帧（含 SPS/PPS 的关键帧）启动解码器
            if (key && w > 0 && h > 0) start(w, h) else return
        }
        val f = Frame(frameId, key, w, h, data, headerLen)
        while (!queue.offer(f)) {
            val dropped = dropOldestNonKey() ?: return
            onLostFrame(dropped.frameId)
        }
    }

    private fun dropOldestNonKey(): Frame? {
        val it = queue.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (!f.key) { it.remove(); return f }
        }
        return null
    }

    fun flush() { pendingFlush = true }

    fun stop() {
        running.set(false)
        queue.clear()
        try { codec?.stop() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
        codec = null
        thread?.interrupt()
        thread = null
        lastQueuedId = -1
    }

    fun release() = stop()

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        val c = codec ?: return
        var pts = SystemClock.elapsedRealtimeNanos() / 1000
        while (running.get()) {
            try {
                if (pendingFlush) {
                    pendingFlush = false
                    try { c.flush() } catch (e: Exception) {}
                    pts = SystemClock.elapsedRealtimeNanos() / 1000
                }
                // 输入
                val f = queue.poll(8, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (f != null) {
                    val inIdx = c.dequeueInputBuffer(8L)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        buf.clear()
                        val len = f.data.size - f.offset
                        if (buf.capacity() >= len) {
                            buf.put(f.data, f.offset, len)
                            pts += 16_667   // 60fps 基准，单调递增即可
                            c.queueInputBuffer(inIdx, 0, len, pts, 0)
                        } else {
                            // 超大帧：直接拆解无效，丢弃并上报
                            onLostFrame(f.frameId)
                        }
                        lastQueuedId = f.frameId
                        if (f.key && (f.w != decW || f.h != decH)) {
                            // 关键帧头声明的新几何：通知 UI 适配
                            onFormatChanged(f.w, f.h); decW = f.w; decH = f.h
                        }
                    } else {
                        // 输入缓冲紧张：重新入队尾（保持顺序）或丢弃
                        queue.offer(f)
                    }
                }
                // 输出（渲染到 Surface）
                var out = c.dequeueOutputBuffer(info, 0L)
                while (out >= 0) {
                    if (info.size > 0) c.releaseOutputBuffer(out, true)
                    else c.releaseOutputBuffer(out, false)
                    out = c.dequeueOutputBuffer(info, 0L)
                }
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = c.outputFormat
                    val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    if (w != decW || h != decH) { decW = w; decH = h; onFormatChanged(w, h) }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "解码循环异常", e)
                break
            }
        }
    }
}
