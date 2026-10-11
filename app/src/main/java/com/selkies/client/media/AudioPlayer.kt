package com.selkies.client.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.selkies.client.net.ConnectionEngine
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频播放器 —— Opus 硬解码 + AudioTrack。
 *
 * 协议要点（核对自官方 selkies-ws-core 的 extractOpusFrames 与 pcmflux 输出）：
 *  - 0x01 二进制帧携带原始 Opus 分组（非 Ogg 封装）；
 *  - RED 冗余已在引擎层（Protocol.parseAudioFrames）按序去重；
 *  - pcmflux 默认 48kHz 双声道；
 *  - MediaCodec 的 audio/opus 解码器需要 csd-0 = OpusHead 识别头（19 字节）。
 *
 * 容错降级：设备无 audio/opus 解码器时 start() 返回 false，
 * 上层显示“音频不可用”提示，会话不受影响。
 *
 * 线程模型：单工作线程 + 有界队列（64 帧 ≈ 1.3s @20ms/帧）。
 * 队列满时丢最老帧（音频宁缺毋堵，避免延迟累积）。
 */
class AudioPlayer : ConnectionEngine.MediaSink {

    @Volatile var enabled: Boolean = true   // 用户开关
    @Volatile var started = false
        private set

    /** 音频不可用原因（设备不支持等），主线程读取 */
    @Volatile var unavailableReason: String? = null
        private set

    private val queue = LinkedBlockingQueue<List<Pair<Long, ByteArray>>>(QUEUE_CAP)
    private val exited = AtomicBoolean(false)
    private var worker: Thread? = null

    // ---- 解码器/播放器状态（仅工作线程访问） ----
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null

    /** 标准OpusHead：48kHz 立体声，预跳过 312 样本 */
    private fun opusHead(): ByteArray = byteArrayOf(
        0x4f, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, // "OpusHead"
        0x01,       // version
        0x02,       // channels = 2
        0x38, 0x01, // preskip = 312 (LE)
        0x80.toByte(), 0xBB.toByte(), 0x00, 0x00, // 48000 Hz (LE)
        0x00, 0x00, // output gain
        0x00        // mapping family
    )

    /** 启动音频管线（引擎收到首批音频帧前调用）。false=设备不支持 */
    @Synchronized
    fun start(): Boolean {
        if (started || exited.get()) return started
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(opusHead()))
                // 标准注释：多声道时需要 csd-1（comment 头），立体声映射族 0 可省
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            c.configure(format, null, null, 0)
            c.start()
            codec = c

            val minBuf = AudioTrack.getMinBufferSize(
                48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
                maxOf(minBuf * 4, 48_000 * 4), // ≥4x 最小缓冲
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            t.play()
            track = t

            worker = Thread(this::workerLoop).apply {
                name = "selkies-audio"
                isDaemon = true
                start()
            }
            started = true
            unavailableReason = null
            Log.i(TAG, "audio pipeline started (opus 48k stereo)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "audio unavailable: ${e.message}")
            unavailableReason = e.message ?: "no opus decoder"
            stop()
            false
        }
    }

    /** 停止并释放（幂等） */
    @Synchronized
    fun stop() {
        started = false
        try {
            worker?.interrupt()
        } catch (_: Exception) {
        }
        worker = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        queue.clear()
    }

    /** 彻底销毁 */
    fun release() {
        exited.set(true)
        stop()
    }

    // ------------------------------------------------------------------
    // MediaSink（WS 线程调用；仅音频，其他空实现）
    // ------------------------------------------------------------------

    override fun onVideoFrame(
        header: com.selkies.client.net.Protocol.VideoFrameHeader,
        data: ByteArray, payloadOffset: Int, payloadLen: Int
    ) {
    }

    override fun onJpegStripe(
        header: com.selkies.client.net.Protocol.JpegStripeHeader,
        data: ByteArray, payloadOffset: Int, payloadLen: Int
    ) {
    }

    override fun onAudioFrames(frames: List<Pair<Long, ByteArray>>) {
        if (!started || !enabled || exited.get()) return
        // 队满丢老，防延迟累积
        if (!queue.offer(frames)) {
            queue.poll()
            queue.offer(frames)
        }
    }

    override fun onPipelineReset() { /* 音频不受视频管道重置影响 */ }

    override fun onVideoInactive() {}

    // ------------------------------------------------------------------
    // 工作循环：Opus → PCM → AudioTrack
    // ------------------------------------------------------------------

    private fun workerLoop() {
        val info = MediaCodec.BufferInfo()
        while (!exited.get() && !Thread.currentThread().isInterrupted) {
            val batch = try {
                queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue

            val c = codec ?: break
            val t = track ?: break
            try {
                if (!enabled) continue // 静音开关：丢弃（不解码不写入）

                // 入队所有 Opus 分组
                for ((_, opus) in batch) {
                    if (opus.isEmpty()) continue
                    val inIdx = c.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx) ?: continue
                        buf.clear()
                        if (opus.size <= buf.remaining()) {
                            buf.put(opus)
                            c.queueInputBuffer(inIdx, 0, opus.size, System.nanoTime() / 1000, 0)
                        } else {
                            c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                        }
                    }
                }
                // 取出 PCM 并写入 AudioTrack
                while (true) {
                    val outIdx = c.dequeueOutputBuffer(info, 0)
                    when {
                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val out = c.getOutputBuffer(outIdx)
                                if (out != null) {
                                    val chunk = ByteArray(info.size)
                                    out.position(info.offset)
                                    out.limit(info.offset + info.size)
                                    out.get(chunk)
                                    try {
                                        t.write(chunk, 0, chunk.size)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            c.releaseOutputBuffer(outIdx, false)
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.i(TAG, "audio output format: ${c.outputFormat}")
                        }
                        else -> break
                    }
                }
            } catch (e: Exception) {
                // 解码异常：重建管线自愈
                Log.w(TAG, "audio loop error: ${e.message} — restarting pipeline")
                try {
                    c.stop()
                    c.release()
                } catch (_: Exception) {
                }
                codec = null
                try {
                    val format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2
                    ).apply { setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(opusHead())) }
                    val nc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                    nc.configure(format, null, null, 0)
                    nc.start()
                    codec = nc
                } catch (e2: Exception) {
                    Log.w(TAG, "audio restart failed: ${e2.message}")
                    unavailableReason = e2.message
                    break // 放弃音频，视频会话继续
                }
            }
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"
        private const val QUEUE_CAP = 64
    }
}
