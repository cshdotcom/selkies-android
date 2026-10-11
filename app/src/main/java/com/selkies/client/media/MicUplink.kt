package com.selkies.client.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import com.selkies.client.net.ConnectionEngine
import com.selkies.client.net.Protocol

/**
 * 麦克风上行 —— 官方 websockets 模式 0x02 链路。
 *
 * 协议（对照 selkies-ws-core.js startMicrophoneCapture + websockets_mode.py 0x02 分支）：
 *  - 24 kHz / 单声道 / Opus；每包一个 Opus 分组，帧 = [0x02][Opus bytes]；
 *  - 无需 start 消息：服务器收到数据即视为开启（"data means it is on"），
 *    服务器在远端把音频写入 PulseAudio/PipeWire 虚拟麦克风设备；
 *  - viewer 角色或策略禁用 → 服务器回 MICROPHONE_DISABLED，UI 层停止并提示。
 *
 * 编码器：MediaCodec audio/opus（Android 10+ 平台普遍内置；运行时探测，
 * 不可用时返回明确错误提示）。
 */
class MicUplink(
    private val engine: ConnectionEngine,
    private val onError: (String) -> Unit
) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null
    private var codec: MediaCodec? = null

    val isRunning: Boolean get() = running

    companion object {
        private const val TAG = "MicUplink"
        const val SAMPLE_RATE = 24000
        const val CHANNELS = 1
        const val BITRATE = 64000
        /** 40ms/帧（对齐官方 24kHz 下 960 采样） */
        const val FRAME_SAMPLES = 960

        /** 设备是否存在 Opus 编码器 */
        fun encoderAvailable(): Boolean = try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.findEncoderForFormat(MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_OPUS)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS)
            }) != null
        } catch (_: Exception) {
            false
        }
    }

    /** 启动上行（调用前须已获 RECORD_AUDIO 权限） */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!encoderAvailable()) {
            onError("Opus encoder unavailable on this device")
            return false
        }
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, FRAME_SAMPLES * 2 * 4)

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // 回声消除/降噪（对齐官方 echoCancellation）
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                onError("microphone init failed")
                return false
            }
            recorder = rec

            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_OPUS)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c

            running = true
            rec.startRecording()
            thread = Thread { loop() }.apply { name = "mic-uplink"; start() }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            stopInternal()
            onError(e.message ?: "mic start failed")
            return false
        }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        running = false
        try {
            thread?.join(300)
        } catch (_: Exception) {
        }
        thread = null
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
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

    /** 采集 + 编码 + 发送主循环 */
    private fun loop() {
        val pcmBuf = ShortArray(FRAME_SAMPLES)
        val bufferInfo = MediaCodec.BufferInfo()
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                // 1. PCM 采集 → 编码器输入
                val codec = this.codec ?: break
                val rec = this.recorder ?: break
                val n = rec.read(pcmBuf, 0, pcmBuf.size)
                if (n > 0 && engine.canSendUplink()) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        for (i in 0 until n) inBuf.putShort(pcmBuf[i])
                        codec.queueInputBuffer(inIdx, 0, n * 2, System.nanoTime() / 1000, 0)
                    }
                }

                // 2. 编码器输出 → 0x02 帧
                var outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
                while (outIdx >= 0) {
                    if (bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        val opus = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.get(opus)
                        if (engine.canSendUplink()) {
                            val frame = ByteArray(1 + opus.size)
                            frame[0] = Protocol.BINARY_MIC.toByte()
                            System.arraycopy(opus, 0, frame, 1, opus.size)
                            engine.sendBinary(frame)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "mic loop: ${e.message}")
                if (running) {
                    stopInternal()
                    onError(e.message ?: "mic error")
                    break
                }
            }
        }
    }
}
