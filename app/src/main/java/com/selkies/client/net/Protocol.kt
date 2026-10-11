package com.selkies.client.net

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Selkies v2 WebSocket 流协议 —— 消息常量与帧解析。
 *
 * 所有格式均核对自官方源码：
 *  - 前端: selkies-web-core/selkies-ws-core.js + lib/wire-codecs.js
 *  - 服务端: selkies/websockets_mode.py + input_handler.py
 *
 * 文本帧 = 控制消息；二进制帧按首字节分类：
 *  0x01 Opus 音频（含 RED 冗余布局）
 *  0x02 客户端麦克风 PCM（本客户端不发送）
 *  0x03 JPEG 条带
 *  0x04 视频条带/整帧（H.264 Annex-B）
 *  0x05 gzip 压缩的控制文本（本客户端不宣告 _gz，服务端保持明文）
 */
object Protocol {

    // ---------- 端点 ----------
    /** WS 主端点（Selkies v2） */
    const val WS_PATH_PRIMARY = "/api/websockets"
    /** WS 回退端点（旧版部署） */
    const val WS_PATH_FALLBACK = "/api/ws"
    /** 健康检查端点（免认证），探测用 */
    const val HTTP_STATUS_PATH = "/api/status"
    /** 文件上传端点 */
    const val HTTP_UPLOAD_PATH = "/api/upload"

    // ---------- 二进制帧类型 ----------
    const val BINARY_AUDIO = 0x01
    const val BINARY_MIC = 0x02
    const val BINARY_JPEG = 0x03
    const val BINARY_VIDEO = 0x04
    const val BINARY_GZIP_TEXT = 0x05
    const val BINARY_WEBCAM = 0x06

    /** 音频帧第二字节的“静音门关闭”标记 */
    const val AUDIO_QUIET = 0x80

    // ---------- 编码 id（0x04 帧首部第二字节高 4 位） ----------
    const val CODEC_H264 = 1
    const val CODEC_VP8 = 2
    const val CODEC_VP9 = 3
    const val CODEC_AV1 = 4
    const val CODEC_H265 = 5

    fun codecName(id: Int): String = when (id) {
        CODEC_H264 -> "h264"
        CODEC_VP8 -> "vp8"
        CODEC_VP9 -> "vp9"
        CODEC_AV1 -> "av1"
        CODEC_H265 -> "h265"
        else -> "h264"
    }

    /** MediaCodec MIME 映射 */
    fun codecMime(id: Int): String = when (id) {
        CODEC_H264 -> "video/avc"
        CODEC_H265 -> "video/hevc"
        CODEC_VP8 -> "video/x-vnd.on2.vp8"
        CODEC_VP9 -> "video/x-vnd.on2.vp9"
        CODEC_AV1 -> "video/av01"
        else -> "video/avc"
    }

    /** 0x04 帧首部第二字节：低 4 位 == 0x01 表示关键帧（解码切入点） */
    fun isKeyFrame(typeByte: Int): Boolean = (typeByte and 0x0f) == 0x01

    /**
     * 解析 0x04 视频帧首部（12 字节，大端）：
     *  byte0     = 0x04
     *  byte1     = (codecId << 4) | keyFlag
     *  byte2-3   = u16 frameId
     *  byte4-5   = u16 stripeY（条带起始行，整帧为 0）
     *  byte6-7   = u16 width
     *  byte8-9   = u16 height
     *  byte10-11 = u16 referenceFrameId
     */
    data class VideoFrameHeader(
        val codecId: Int,
        val keyFrame: Boolean,
        val frameId: Int,
        val stripeY: Int,
        val width: Int,
        val height: Int,
        val referenceId: Int
    )

    fun parseVideoHeader(data: ByteArray, offset: Int = 0): VideoFrameHeader? {
        if (data.size - offset < 12) return null
        val b = data
        val o = offset
        val typeByte = b[o + 1].toInt() and 0xff
        return VideoFrameHeader(
            codecId = (typeByte ushr 4) and 0x0f,
            keyFrame = (typeByte and 0x0f) == 0x01,
            frameId = ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff),
            stripeY = ((b[o + 4].toInt() and 0xff) shl 8) or (b[o + 5].toInt() and 0xff),
            width = ((b[o + 6].toInt() and 0xff) shl 8) or (b[o + 7].toInt() and 0xff),
            height = ((b[o + 8].toInt() and 0xff) shl 8) or (b[o + 9].toInt() and 0xff),
            referenceId = ((b[o + 10].toInt() and 0xff) shl 8) or (b[o + 11].toInt() and 0xff)
        )
    }

    /**
     * 解析 0x03 JPEG 条带首部（6 字节）：
     *  byte0   = 0x03
     *  byte1   = u8 保留
     *  byte2-3 = u16 frameId
     *  byte4-5 = u16 stripeY
     */
    data class JpegStripeHeader(val frameId: Int, val stripeY: Int)

    fun parseJpegHeader(data: ByteArray): JpegStripeHeader? {
        if (data.size < 6) return null
        return JpegStripeHeader(
            frameId = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff),
            stripeY = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        )
    }

    /**
     * 解析 0x01 音频帧为“按解码顺序排列的 Opus 分组”。
     * 端口自官方 extractOpusFrames（selkies-ws-core.js）：
     *
     *  - [0x01, AUDIO_QUIET]（仅 2 字节）：静音门关闭标记，无数据
     *  - n_red == 0：[0x01, 0x00] + opus —— 普通路径
     *  - n_red > 0：[0x01, n_red, pts32BE] + n_red*(4B 头) + 1B 主头 + 数据块
     *    （冗余块按从旧到新排列，最后是主块；每块时间戳 = pts - tsOffset）
     *    4 字节头：1 字节跳过 + 3 字节字段（offset:14bit | len:10bit）
     *
     * @param lastPts 上一次已播放的时间戳（用于 RED 去重），首次传 null
     * @return (时间戳, Opus 数据) 列表 —— 调用方按顺序送解码器，并更新 lastPts
     */
    fun parseAudioFrames(data: ByteArray, lastPts: Long?): Pair<List<Pair<Long, ByteArray>>, Long?> {
        if (data.size <= 2) return emptyList<Pair<Long, ByteArray>>() to lastPts
        val nRed = data[1].toInt() and (AUDIO_QUIET.inv() and 0xff)
        if (nRed == 0) {
            // 普通帧：没有 pts，用 lastPts + 20ms（Opus 一帧 20ms）保序即可
            return listOf(Pair(-1L, data.copyOfRange(2, data.size))) to lastPts
        }
        // RED 冗余帧
        if (data.size < 6 + nRed * 4 + 1) return emptyList<Pair<Long, ByteArray>>() to null
        val pts = ((data[2].toLong() and 0xff) shl 24) or
                ((data[3].toLong() and 0xff) shl 16) or
                ((data[4].toLong() and 0xff) shl 8) or
                (data[5].toLong() and 0xff)
        var pos = 6
        val offsets = IntArray(nRed)
        val lens = IntArray(nRed)
        for (i in 0 until nRed) {
            val field = ((data[pos + 1].toLong() and 0xff) shl 16) or
                    ((data[pos + 2].toLong() and 0xff) shl 8) or
                    (data[pos + 3].toLong() and 0xff)
            offsets[i] = ((field shr 10) and 0x3fff).toInt()
            lens[i] = (field and 0x3ff).toInt()
            pos += 4
        }
        pos += 1 // 主头 1 字节
        var declared = pos
        for (i in 0 until nRed) declared += lens[i]
        if (declared > data.size) return emptyList<Pair<Long, ByteArray>>() to null

        val blocks = ArrayList<Pair<Long, ByteArray>>(nRed + 1)
        for (i in 0 until nRed) {
            blocks.add(Pair((pts - offsets[i]) and 0xffffffffL, data.copyOfRange(pos, pos + lens[i])))
            pos += lens[i]
        }
        blocks.add(Pair(pts, data.copyOfRange(pos, data.size)))

        // 去重：只取比上次播放更新的块，按时间顺序解码
        val out = ArrayList<Pair<Long, ByteArray>>(blocks.size)
        var newLast = lastPts
        if (lastPts == null) {
            // 第一个 RED 包：锚定在主块上，不重放冗余
            out.add(blocks.last())
            newLast = blocks.last().first
        } else {
            for (b in blocks) {
                if (b.first > lastPts) {
                    out.add(b)
                    val known = newLast
                    if (known == null || b.first > known) newLast = b.first
                }
            }
        }
        return out to newLast
    }

    /** 解压 0x05 gzip 控制文本 */
    fun gunzip(data: ByteArray, offset: Int = 1): String = try {
        val ins = GZIPInputStream(java.io.ByteArrayInputStream(data, offset, data.size - offset))
        ins.readBytes().toString(Charsets.UTF_8).also { ins.close() }
    } catch (e: Exception) {
        ""
    }

    // ---------- 控制消息构造 ----------

    /**
     * 构造初始 SETTINGS 载荷（对齐官方 getCurrentSettingsPayload 的关键键）。
     * 分辨率必须为偶数（服务端对齐要求），上限 4080。
     */
    fun buildSettingsJson(
        clientWidth: Int,
        clientHeight: Int,
        framerate: Int,
        crf: Int,
        bitrateKbps: Int,
        rateControl: String,
        audioBitrate: Int,
        binaryClipboard: Boolean = true
    ): String {
        val w = alignResolution(clientWidth).coerceAtMost(4080)
        val h = alignResolution(clientHeight).coerceAtMost(4080)
        val o = JSONObject()
        o.put("framerate", framerate)
        o.put("encoder", "h264enc")           // 请求整帧 H.264（硬解主路径）
        o.put("video_crf", crf)
        o.put("video_bitrate", bitrateKbps)
        o.put("rate_control_mode", rateControl)
        o.put("audio_bitrate", audioBitrate)
        o.put("manual_resolution", false)
        o.put("initialClientWidth", w)
        o.put("initialClientHeight", h)
        o.put("video_fullcolor", false)
        o.put("video_10bit", false)
        o.put("use_cpu", false)
        o.put("use_paint_over_quality", true)
        o.put("scaling_dpi", 96)
        o.put("enable_binary_clipboard", binaryClipboard)
        o.put("force_aligned_resolution", false)
        o.put("useCssScaling", false)
        o.put("displayId", "primary")
        o.put("tabId", java.util.UUID.randomUUID().toString().substring(0, 8))
        o.put("displayScale", 1)
        o.put("audioRedundancy", true)
        return o.toString()
    }

    /** 分辨率对齐到偶数 */
    fun alignResolution(v: Int): Int = if (v % 2 == 0) v else v - 1

    /** u16 大端写入 */
    fun u16(v: Int): ByteArray = byteArrayOf(
        ((v shr 8) and 0xff).toByte(),
        (v and 0xff).toByte()
    )

    /** 解析 `r,WxH,displayId`（部分旧版服务端会以此广播已实现分辨率） */
    fun parseResolutionMessage(msg: String): Triple<Int, Int, String>? {
        // r,1920x1080,primary
        if (!msg.startsWith("r,")) return null
        val parts = msg.split(",")
        if (parts.size < 3) return null
        val dims = parts[1].split("x")
        if (dims.size != 2) return null
        return try {
            Triple(dims[0].trim().toInt(), dims[1].trim().toInt(), parts[2].trim())
        } catch (_: NumberFormatException) {
            null
        }
    }
}
