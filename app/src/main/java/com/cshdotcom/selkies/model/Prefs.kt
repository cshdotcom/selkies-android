package com.cshdotcom.selkies.model

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局应用设置（画质/手势/行为）。均带默认值。
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    // ---- 画质 ----
    var videoBitrateKbps: Int
        get() = sp.getInt("video_bitrate", 8000)
        set(v) = sp.edit().putInt("video_bitrate", v).apply()
    var framerate: Int
        get() = sp.getInt("framerate", 60)
        set(v) = sp.edit().putInt("framerate", v).apply()
    /** 连接时把远端分辨率调成匹配手机屏幕（发送 r,WxH） */
    var matchResolution: Boolean
        get() = sp.getBoolean("match_resolution", true)
        set(v) = sp.edit().putBoolean("match_resolution", v).apply()
    /** 本次连接的目标分辨率（RemoteActivity 连接前写入） */
    var matchResolutionWidth: Int
        get() = sp.getInt("match_res_w", 0)
        set(v) = sp.edit().putInt("match_res_w", v).apply()
    var matchResolutionHeight: Int
        get() = sp.getInt("match_res_h", 0)
        set(v) = sp.edit().putInt("match_res_h", v).apply()

    // ---- 音频 ----
    var audioEnabled: Boolean
        get() = sp.getBoolean("audio_enabled", true)
        set(v) = sp.edit().putBoolean("audio_enabled", v).apply()
    var audioVolume: Int
        get() = sp.getInt("audio_volume", 100)
        set(v) = sp.edit().putInt("audio_volume", v).apply()

    // ---- 行为 ----
    var clipboardSync: Boolean
        get() = sp.getBoolean("clipboard_sync", true)
        set(v) = sp.edit().putBoolean("clipboard_sync", v).apply()
    var autoReconnect: Boolean
        get() = sp.getBoolean("auto_reconnect", true)
        set(v) = sp.edit().putBoolean("auto_reconnect", v).apply()
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()
    var autoShowKeyboard: Boolean
        get() = sp.getBoolean("auto_keyboard", false)
        set(v) = sp.edit().putBoolean("auto_keyboard", v).apply()

    // ---- 手势 ----
    /** 长按触发动作：0=右键 1=中键 2=无 */
    var longPressAction: Int
        get() = sp.getInt("gesture_long_press", 0)
        set(v) = sp.edit().putInt("gesture_long_press", v).apply()
    /** 双指滑动自然（触控板式）滚动方向 */
    var naturalScroll: Boolean
        get() = sp.getBoolean("natural_scroll", false)
        set(v) = sp.edit().putBoolean("natural_scroll", v).apply()
    /** 触摸移动灵敏度（1~10，映射到像素阈值与滚动步长） */
    var touchSensitivity: Int
        get() = sp.getInt("touch_sensitivity", 5)
        set(v) = sp.edit().putInt("touch_sensitivity", v).apply()
    /** 双指滚动的像素阈值：滚动一格所需的滑动像素 */
    var scrollStepPx: Int
        get() = sp.getInt("scroll_step_px", 50)
        set(v) = sp.edit().putInt("scroll_step_px", v).apply()

    // ---- 键盘 ----
    /** 键盘布局上报（服务端用于键映射） */
    var keyboardLayout: String
        get() = sp.getString("keyboard_layout", "us") ?: "us"
        set(v) = sp.edit().putString("keyboard_layout", v).apply()
}
