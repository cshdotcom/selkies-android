package com.selkies.client.input

import android.view.KeyEvent

/**
 * X11 Keysym 表 —— 与官方 lib/keyboard-util.js（Guacamole 系 KeyTable）对齐。
 *
 * 规则：
 *  - 可打印 ASCII（0x20–0x7E）：keysym == 码点
 *  - Latin-1 补充（0xA0–0xFF）：keysym == 码点
 *  - 功能键/修饰键使用 X11 常量（0xff?? 段）
 *  - F1–F12：0xffbe–0xffc9
 */
object Keysyms {
    // ---- 修饰键 ----
    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CTRL_L = 0xffe3
    const val CTRL_R = 0xffe4
    const val CAPS_LOCK = 0xffe5
    const val META_L = 0xffe7   // Meta
    const val META_R = 0xffe8
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea    // AltGr
    const val SUPER_L = 0xffeb  // Win 键
    const val SUPER_R = 0xffec
    const val HYPER_L = 0xffed
    const val HYPER_R = 0xffee

    // ---- 控制键 ----
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val LINEFEED = 0xff0a
    const val CLEAR = 0xff0b
    const val RETURN = 0xff0d
    const val PAUSE = 0xff13
    const val SCROLL_LOCK = 0xff14
    const val SYS_REQ = 0xff15
    const val ESCAPE = 0xff1b
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val BEGIN = 0xff58

    // ---- 功能键 ----
    const val F1 = 0xffbe
    const val F2 = 0xffbf
    const val F3 = 0xffc0
    const val F4 = 0xffc1
    const val F5 = 0xffc2
    const val F6 = 0xffc3
    const val F7 = 0xffc4
    const val F8 = 0xffc5
    const val F9 = 0xffc6
    const val F10 = 0xffc7
    const val F11 = 0xffc8
    const val F12 = 0xffc9

    // ---- 数字区 ----
    const val KP_0 = 0xffb0
    const val KP_1 = 0xffb1
    const val KP_2 = 0xffb2
    const val KP_3 = 0xffb3
    const val KP_4 = 0xffb4
    const val KP_5 = 0xffb5
    const val KP_6 = 0xffb6
    const val KP_7 = 0xffb7
    const val KP_8 = 0xffb8
    const val KP_9 = 0xffb9
    const val KP_MULTIPLY = 0xffaa
    const val KP_ADD = 0xffab
    const val KP_SUBTRACT = 0xffad
    const val KP_DECIMAL = 0xffae
    const val KP_DIVIDE = 0xffaf
    const val KP_ENTER = 0xff8d

    // ---- 其他 ----
    const val SPACE = 0x0020
    const val DELETE = 0xffff
    const val INSERT = 0xff63
    const val MULTI_KEY = 0xff20
    const val PRINT = 0xff61
    const val SELECT = 0xff60
    const val HELP = 0xff6a
    const val MENU = 0xff67
    const val NUM_LOCK = 0xff7f

    /** 主键（可选修饰键组合）名称反查：快捷键显示用 */
    fun nameOf(keysym: Int): String? = when (keysym) {
        BACKSPACE -> "Backspace"; TAB -> "Tab"; RETURN -> "Enter"; ESCAPE -> "Esc"
        SPACE -> "Space"; DELETE -> "Del"; INSERT -> "Ins"
        HOME -> "Home"; END -> "End"; PAGE_UP -> "PgUp"; PAGE_DOWN -> "PgDn"
        LEFT -> "←"; UP -> "↑"; RIGHT -> "→"; DOWN -> "↓"
        F1 -> "F1"; F2 -> "F2"; F3 -> "F3"; F4 -> "F4"; F5 -> "F5"; F6 -> "F6"
        F7 -> "F7"; F8 -> "F8"; F9 -> "F9"; F10 -> "F10"; F11 -> "F11"; F12 -> "F12"
        PRINT -> "Print"; PAUSE -> "Pause"; SCROLL_LOCK -> "ScrLk"
        CAPS_LOCK -> "CapsLk"; NUM_LOCK -> "NumLk"; MENU -> "Menu"
        KP_ENTER -> "NumEnter"
        CTRL_L, CTRL_R -> "Ctrl"; ALT_L, ALT_R -> "Alt"
        SHIFT_L, SHIFT_R -> "Shift"; SUPER_L, SUPER_R -> "Super"
        else -> when {
            keysym in 0x21..0x7e -> keysym.toChar().toString()
            keysym in 'a'.code..'z'.code -> keysym.toChar().uppercase()
            keysym in 'A'.code..'Z'.code -> keysym.toChar().toString()
            else -> null
        }
    }

    /** Android metaState（物理键盘修饰键状态）→ X11 修饰键 keysym 集合 */
    fun modifiersFromMetaState(metaState: Int): List<Int> = buildList {
        if (metaState and KeyEvent.META_CTRL_ON != 0) add(CTRL_L)
        if (metaState and KeyEvent.META_ALT_ON != 0) add(ALT_L)
        if (metaState and KeyEvent.META_SHIFT_ON != 0) add(SHIFT_L)
        if (metaState and KeyEvent.META_META_ON != 0) add(SUPER_L)
    }

    fun isModifierKeysym(keysym: Int): Boolean = keysym in MODIFIER_KEYSYMS

    val MODIFIER_KEYSYMS = setOf(
        SHIFT_L, SHIFT_R, CTRL_L, CTRL_R, ALT_L, ALT_R,
        SUPER_L, SUPER_R, META_L, META_R
    )

    /**
     * Android KeyEvent → X11 keysym（覆盖硬键盘/蓝牙键盘主键位）。
     * 返回 null 表示无需转发（音量键等交还系统）。
     */
    fun fromAndroidKeyCode(keyCode: Int, event: KeyEvent?): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DEL -> DELETE
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_INSERT -> INSERT
        KeyEvent.KEYCODE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER -> RETURN
        KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT_L
        KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_R
        KeyEvent.KEYCODE_CTRL_LEFT -> CTRL_L
        KeyEvent.KEYCODE_CTRL_RIGHT -> CTRL_R
        KeyEvent.KEYCODE_ALT_LEFT -> ALT_L
        KeyEvent.KEYCODE_ALT_RIGHT -> ALT_R
        KeyEvent.KEYCODE_META_LEFT -> SUPER_L
        KeyEvent.KEYCODE_META_RIGHT -> SUPER_R
        KeyEvent.KEYCODE_CAPS_LOCK -> CAPS_LOCK
        KeyEvent.KEYCODE_NUM_LOCK -> NUM_LOCK
        KeyEvent.KEYCODE_SCROLL_LOCK -> SCROLL_LOCK
        KeyEvent.KEYCODE_F1 -> F1
        KeyEvent.KEYCODE_F2 -> F2
        KeyEvent.KEYCODE_F3 -> F3
        KeyEvent.KEYCODE_F4 -> F4
        KeyEvent.KEYCODE_F5 -> F5
        KeyEvent.KEYCODE_F6 -> F6
        KeyEvent.KEYCODE_F7 -> F7
        KeyEvent.KEYCODE_F8 -> F8
        KeyEvent.KEYCODE_F9 -> F9
        KeyEvent.KEYCODE_F10 -> F10
        KeyEvent.KEYCODE_F11 -> F11
        KeyEvent.KEYCODE_F12 -> F12
        KeyEvent.KEYCODE_SPACE -> SPACE
        KeyEvent.KEYCODE_NUMPAD_0 -> KP_0
        KeyEvent.KEYCODE_NUMPAD_1 -> KP_1
        KeyEvent.KEYCODE_NUMPAD_2 -> KP_2
        KeyEvent.KEYCODE_NUMPAD_3 -> KP_3
        KeyEvent.KEYCODE_NUMPAD_4 -> KP_4
        KeyEvent.KEYCODE_NUMPAD_5 -> KP_5
        KeyEvent.KEYCODE_NUMPAD_6 -> KP_6
        KeyEvent.KEYCODE_NUMPAD_7 -> KP_7
        KeyEvent.KEYCODE_NUMPAD_8 -> KP_8
        KeyEvent.KEYCODE_NUMPAD_9 -> KP_9
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> KP_MULTIPLY
        KeyEvent.KEYCODE_NUMPAD_ADD -> KP_ADD
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> KP_SUBTRACT
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> KP_DIVIDE
        KeyEvent.KEYCODE_NUMPAD_DOT -> KP_DECIMAL
        KeyEvent.KEYCODE_MINUS -> 0x002d
        KeyEvent.KEYCODE_EQUALS -> 0x003d
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x005b
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x005d
        KeyEvent.KEYCODE_BACKSLASH -> 0x005c
        KeyEvent.KEYCODE_SEMICOLON -> 0x003b
        KeyEvent.KEYCODE_APOSTROPHE -> 0x0027
        KeyEvent.KEYCODE_COMMA -> 0x002c
        KeyEvent.KEYCODE_PERIOD -> 0x002e
        KeyEvent.KEYCODE_SLASH -> 0x002f
        KeyEvent.KEYCODE_GRAVE -> 0x0060
        KeyEvent.KEYCODE_AT -> 0x0040
        KeyEvent.KEYCODE_PLUS -> 0x002b
        KeyEvent.KEYCODE_STAR -> 0x002a
        KeyEvent.KEYCODE_POUND -> 0x0023
        else -> {
            // 字母与数字：A–Z / 0–9 直接映射码点
            if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                'a'.code + (keyCode - KeyEvent.KEYCODE_A)
            } else if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                '0'.code + (keyCode - KeyEvent.KEYCODE_0)
            } else {
                // Unicode 字符兜底（可打印范围）
                val uni = event?.unicodeChar ?: 0
                if (uni in 0x20..0xffe && uni !in 0xd800..0xdfff) uni else null
            }
        }
    }
}
