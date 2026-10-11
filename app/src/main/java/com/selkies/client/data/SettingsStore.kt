package com.selkies.client.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局应用设置（画质 / 手势 / 行为开关）。
 * 会话级参数（CRF、码率、帧率等）在建立连接时打包进 SETTINGS 消息发给服务器。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---------- 画质与性能 ----------

    /** 视频 CRF 恒定质量值（越小画质越高），默认 25 */
    var videoCrf: Int
        get() = prefs.getInt("video_crf", 25)
        set(v) = prefs.edit().putInt("video_crf", v.coerceIn(0, 51)).apply()

    /** CBR 模式目标码率 kbps，默认 8000 */
    var videoBitrate: Int
        get() = prefs.getInt("video_bitrate", 8000)
        set(v) = prefs.edit().putInt("video_bitrate", v.coerceIn(500, 100_000)).apply()

    /** 请求帧率，默认 30 */
    var framerate: Int
        get() = prefs.getInt("framerate", 30)
        set(v) = prefs.edit().putInt("framerate", v.coerceIn(10, 60)).apply()

    /** 码率控制模式：cbr / crf */
    var rateControl: String
        get() = prefs.getString("rate_control", "cbr") ?: "cbr"
        set(v) = prefs.edit().putString("rate_control", v).apply()

    /** 音频码率 bps，默认 320000 */
    var audioBitrate: Int
        get() = prefs.getInt("audio_bitrate", 320000)
        set(v) = prefs.edit().putInt("audio_bitrate", v.coerceIn(6000, 512_000)).apply()

    /** 进入会话时自动请求音频流 */
    var autoAudio: Boolean
        get() = prefs.getBoolean("auto_audio", true)
        set(v) = prefs.edit().putBoolean("auto_audio", v).apply()

    // ---------- 手势与输入 ----------

    /** 双指轻点动作：right / middle */
    var twoFingerTap: String
        get() = prefs.getString("two_finger_tap", "right") ?: "right"
        set(v) = prefs.edit().putString("two_finger_tap", v).apply()

    /** 长按动作：right / middle / drag */
    var longPress: String
        get() = prefs.getString("long_press", "right") ?: "right"
        set(v) = prefs.edit().putString("long_press", v).apply()

    /** 默认触摸模式：trackpad（相对）/ direct（绝对） */
    var touchMode: String
        get() = prefs.getString("touch_mode", "trackpad") ?: "trackpad"
        set(v) = prefs.edit().putString("touch_mode", v).apply()

    /** 服务器分辨率跟随屏幕旋转（发送 r 消息调整桌面分辨率） */
    var followRotation: Boolean
        get() = prefs.getBoolean("follow_rotation", true)
        set(v) = prefs.edit().putBoolean("follow_rotation", v).apply()

    // ---------- 连接行为 ----------

    /** 断线自动重连 */
    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(v) = prefs.edit().putBoolean("auto_reconnect", v).apply()

    /** 自动重连最大尝试次数，0=无限 */
    var maxReconnects: Int
        get() = prefs.getInt("max_reconnects", 10)
        set(v) = prefs.edit().putInt("max_reconnects", v.coerceIn(0, 100)).apply()

    /** 双指捏合缩放（本地视觉缩放，不影响远端） */
    var pinchZoom: Boolean
        get() = prefs.getBoolean("pinch_zoom", true)
        set(v) = prefs.edit().putBoolean("pinch_zoom", v).apply()
}
