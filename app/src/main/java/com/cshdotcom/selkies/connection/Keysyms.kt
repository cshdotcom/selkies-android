package com.cshdotcom.selkies.connection

/**
 * X11 keysym 常量表（对照 selkies 服务端 input_handler.py 与 X11 keysymdef）
 * 说明：拉丁可打印字符的 keysym 即其 Unicode 码点（0x20..0xFF）；
 *       0x01000000 | unicode 用于 latin1 之外的字符。
 */
object Keysyms {
    // 修饰键
    const val Shift_L = 0xffe1
    const val Shift_R = 0xffe2
    const val Control_L = 0xffe3
    const val Control_R = 0xffe4
    const val Caps_Lock = 0xffe5
    const val Shift_Lock = 0xffe6
    const val Meta_L = 0xffe7
    const val Meta_R = 0xffe8
    const val Alt_L = 0xffe9
    const val Alt_R = 0xffea
    const val Super_L = 0xffeb
    const val Super_R = 0xffec
    const val Hyper_L = 0xffed
    const val Hyper_R = 0xffee
    const val Alt_Gr = 0xfe03       // ISO_Level3_Shift

    // 控制键
    const val BackSpace = 0xff08
    const val Tab = 0xff09
    const val Linefeed = 0xff0a
    const val Clear = 0xff0b
    const val Return = 0xff0d       // 回车/Enter
    const val Pause = 0xff13
    const val Scroll_Lock = 0xff14
    const val Sys_Req = 0xff15
    const val Escape = 0xff1b
    const val Delete = 0xffff
    const val Home = 0xff50
    const val Left = 0xff51
    const val Up = 0xff52
    const val Right = 0xff53
    const val Down = 0xff54
    const val Page_Up = 0xff55
    const val Page_Down = 0xff56
    const val End = 0xff57
    const val Begin = 0xff58
    const val Insert = 0xff63
    const val Print = 0xff61
    const val KP_Enter = 0xff8d
    const val KP_Space = 0xff80
    const val KP_Tab = 0xff89
    const val KP_Multiply = 0xffaa
    const val KP_Add = 0xffab
    const val KP_Separator = 0xffac
    const val KP_Subtract = 0xffad
    const val KP_Decimal = 0xffae
    const val KP_Divide = 0xffaf
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
    const val Num_Lock = 0xff7f

    // 功能键 F1..F24（0xffbe..）
    const val F1 = 0xffbe
    fun functionKey(n: Int): Int {
        require(n in 1..24)
        return 0xffbd + n   // F1=0xffbe ... F24=0xffd5
    }

    // Windows / 多媒体
    const val Multi_key = 0xff20      // Compose
    const val Menu = 0xff67
    const val ISO_Left_Tab = 0xfe20

    /** 将任意字符转成 keysym（latin1 直接映射，其余加 0x01000000 前缀） */
    fun charToKeysym(ch: Char): Int {
        val cp = ch.code
        return if (cp in 0x20..0xff) cp else 0x01000000 or cp
    }
}
