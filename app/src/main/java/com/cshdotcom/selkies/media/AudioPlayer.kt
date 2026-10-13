package com.cshdotcom.selkies.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opus 音频解码 + 播放。
 * - 输入：Selkies 0x01 二进制消息（含 RED 冗余布局，对照官方 extractOpusFrames）
 *   [0]=0x01, [1]=flags(bit7=quiet, 低7位=n_red)
 *   n_red=0 → [2..] 单 Opus 包
 *   n_red>0 → [2..5]=pts u32BE, n_red×4B RED 头, 1B 保留, n_red×(len)冗余块, 主块
 * - 解码：MediaCodec "audio/opus"（Android 8.0+ 普遍可用）
 * - 播放：AudioTrack 低延迟流式
 * - 按 pts 去重：只解码比上次新的块
 */
class AudioPlayer {
    companion object { private const val TAG = "AudioPlayer"; private const val SR = 48000; private const val CH = 2 }

    private class Pkt(val ts: Int, val data: ByteArray)

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<Pkt>(256)
    private var lastTs = Int.MIN_VALUE

    /** 音量 0..100 */
    @Volatile var volumePercent: Int = 100

    fun start(): Boolean {
        stop()
        try {
            codec = MediaCodec.createDecoderByType("audio/opus").also { c ->
                val fmt = MediaFormat.createAudioFormat("audio/opus", SR, CH)
                c.configure(fmt, null, null, 0)
                c.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "此设备无 Opus 硬解码器", e)
            return false
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(SR * 2 * 2 * 4)   // 4 倍缓冲
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track?.play()
        running.set(true)
        thread = Thread(this::loop, "selkies-audio").apply { isDaemon = true; start() }
        return true
    }

    /** 输入一条 0x01 二进制消息 */
    fun feed(message: ByteArray) {
        if (!running.get() || message.size < 2) return
        val flags = message[1].toInt() and 0xff
        // 正式解析（对照官方 extractOpusFrames）：低 7 位 = n_red，bit7 = 静默标记
        val n = flags and 0x7f
        val quiet = (flags and 0x80) != 0
        if (message.size <= 2) return   // 纯静默标记
        try {
            if (n == 0) {
                offer(0, message.copyOfRange(2, message.size), quiet)
            } else {
                if (message.size < 6 + n * 4 + 1) return
                val pts = ((message[2].toInt() and 0xff) shl 24) or ((message[3].toInt() and 0xff) shl 16) or
                    ((message[4].toInt() and 0xff) shl 8) or (message[5].toInt() and 0xff)
                var pos = 6
                val offsets = IntArray(n); val lens = IntArray(n)
                for (i in 0 until n) {
                    val field = ((message[pos + 1].toInt() and 0xff) shl 16) or
                        ((message[pos + 2].toInt() and 0xff) shl 8) or (message[pos + 3].toInt() and 0xff)
                    offsets[i] = (field shr 10) and 0x3fff
                    lens[i] = field and 0x3ff
                    pos += 4
                }
                pos += 1
                var declared = pos
                for (i in 0 until n) declared += lens[i]
                if (declared > message.size) return
                for (i in 0 until n) {
                    offer(pts - offsets[i], message.copyOfRange(pos, pos + lens[i]), false)
                    pos += lens[i]
                }
                offer(pts, message.copyOfRange(pos, message.size), quiet)
            }
        } catch (e: Exception) { /* 截断报文丢弃 */ }
    }

    private fun offer(ts: Int, data: ByteArray, quiet: Boolean) {
        if (quiet && data.isEmpty()) return
        if (data.isEmpty()) return
        if (ts <= lastTs && lastTs != Int.MIN_VALUE) return  // 去重旧块
        if (ts > lastTs) lastTs = ts
        queue.offer(Pkt(ts, data))
    }

    fun stop() {
        running.set(false)
        queue.clear()
        try { track?.pause() } catch (e: Exception) {}
        try { track?.release() } catch (e: Exception) {}
        track = null
        try { codec?.stop() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
        codec = null
        thread?.interrupt()
        thread = null
        lastTs = Int.MIN_VALUE
    }

    fun release() = stop()

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        val c = codec ?: return
        var pts = 0L
        while (running.get()) {
            try {
                val pkt = queue.poll(8, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (pkt != null) {
                    val inIdx = c.dequeueInputBuffer(8L)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        buf.clear()
                        if (buf.capacity() >= pkt.data.size) {
                            buf.put(pkt.data)
                            pts += 20_000   // 20ms Opus 帧
                            c.queueInputBuffer(inIdx, 0, pkt.data.size, pts, 0)
                        }
                    } else {
                        queue.offer(pkt)   // 稍后重试
                    }
                }
                var out = c.dequeueOutputBuffer(info, 0L)
                while (out >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(out)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        ob.get(pcm)
                        track?.let { t ->
                            t.setVolume(volumePercent / 100f)
                            t.write(pcm, 0, pcm.size)
                        }
                    }
                    c.releaseOutputBuffer(out, false)
                    out = c.dequeueOutputBuffer(info, 0L)
                }
                if (queue.isEmpty() && out == MediaCodec.INFO_TRY_AGAIN_LATER && pkt == null) {
                    // 空闲节拍
                }
            } catch (e: InterruptedException) { break }
            catch (e: Exception) { Log.e(TAG, "音频循环异常", e); break }
        }
    }
}
