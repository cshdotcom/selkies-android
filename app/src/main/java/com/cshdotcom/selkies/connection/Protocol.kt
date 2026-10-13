package com.cshdotcom.selkies.connection

/**
 * Selkies 2.0 WebSocket 协议常量（逐条对照官方源码 selkies-ws-core.js / websockets_mode.py 校对）
 * 详见 docs/PROTOCOL.md
 */
object Protocol {
    // ---------- 二进制帧首字节标记 ----------
    const val BIN_AUDIO: Int = 0x01       // S->C Opus 音频（含 RED 冗余布局）
    const val BIN_MIC: Int = 0x02         // C->S 麦克风
    const val BIN_JPEG: Int = 0x03        // S->C JPEG 条带
    const val BIN_VIDEO: Int = 0x04       // S->C 视频帧（12 字节头 + H264 Annex-B）
    const val BIN_GZ_TEXT: Int = 0x05     // 双向 gzip 控制文本
    const val VIDEO_HEADER_LEN: Int = 12

    // ---------- 0x04 帧头字段 ----------
    const val CODEC_H264 = 1; const val CODEC_VP8 = 2; const val CODEC_VP9 = 3
    const val CODEC_AV1 = 4; const val CODEC_H265 = 5
    const val AUDIO_QUIET: Int = 0x80     // 音频 flags bit7

    // ---------- 鼠标按键掩码位 ----------
    const val MASK_LEFT: Int = 1 shl 0        // 1
    const val MASK_MIDDLE: Int = 1 shl 1      // 2
    const val MASK_RIGHT: Int = 1 shl 2       // 4
    const val MASK_SCROLL_DOWN: Int = 1 shl 3 // 8
    const val MASK_SCROLL_UP: Int = 1 shl 4   // 16
    const val MASK_ERASER: Int = 1 shl 5      // 32
    const val MASK_SCROLL_LEFT: Int = 1 shl 6 // 64
    const val MASK_SCROLL_RIGHT: Int = 1 shl 7// 128

    // ---------- 周期节奏（对齐官方客户端） ----------
    const val ACK_INTERVAL_MS = 50L          // CLIENT_FRAME_ACK 周期
    const val METRICS_INTERVAL_MS = 500L     // _f/_l 指标周期
    const val KEY_HEARTBEAT_MS = 500L        // kh 心跳周期
    const val GAMEPAD_HEARTBEAT_MS = 100L    // js,h 心跳周期
    const val CLIPBOARD_CHUNK = 16 * 1024 / 3 * 3   // 16383，对齐服务端分块
    const val UPLOAD_CHUNK = 64L * 1024 * 1024      // 64MB 分片

    // ---------- WS 路径（新优先，旧回退） ----------
    val WS_PATHS = arrayOf("/api/websockets", "/api/ws")

    /** 模拟桌面版 Chrome 的握手头，规避 Cloudflare 对异常头的 400 拦截（与官方网页前端一致） */
    const val BROWSER_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    /** 与浏览器直连 WS 握手一致的最小请求头集合 */
    fun browserHeaders(origin: String): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Origin" to origin,
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )
}
