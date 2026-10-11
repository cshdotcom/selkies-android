package com.selkies.client.input

import com.selkies.client.net.ConnectionEngine

/**
 * 输入控制器 —— 键鼠事件的统一出口。
 *
 * 核心职责：
 *  1. **权限门禁**：仅查看模式下，所有输入方法直接丢弃（在引擎 sendInput
 *     门禁之外的又一处防线，确保任何路径都发不出输入）；
 *  2. 修饰键粘滞（sticky modifiers）：虚拟键盘上的 Ctrl/Alt/Shift/Super
 *     按下后保持，直到下一次非修饰键事件后自动释放；
 *  3. 组合键序列（chord）：修饰键按下 → 主键按下/抬起 → 修饰键逆序抬起
 *     （对齐官方 _sendChord 行为）。
 */
class InputController(private val engine: ConnectionEngine) {

    /** 仅查看模式（本地配置） */
    @Volatile var viewOnly: Boolean = false

    // 粘滞修饰键状态（keysym 集合）
    private val heldModifiers = LinkedHashSet<Int>()

    /** 手动按下的修饰键（用户在虚拟键盘上点亮的） */
    val activeModifiers: Set<Int> get() = synchronized(heldModifiers) { heldModifiers.toSet() }

    /** 修饰键开关注销回调（虚拟键盘刷新 UI 用） */
    var onModifiersChanged: (() -> Unit)? = null

    // ------------------------------------------------------------------
    // 门禁
    // ------------------------------------------------------------------

    private fun allowed(): Boolean = !viewOnly && engine.inputAllowed

    // ------------------------------------------------------------------
    // 修饰键管理
    // ------------------------------------------------------------------

    /** 切换粘滞修饰键（虚拟键盘 Ctrl/Alt/Shift/Super 按钮）。返回切换后的状态 */
    fun toggleModifier(keysym: Int): Boolean {
        val on: Boolean
        synchronized(heldModifiers) {
            if (heldModifiers.contains(keysym)) {
                heldModifiers.remove(keysym)
                on = false
                if (allowed()) engine.keyEvent(keysym, false)
            } else {
                heldModifiers.add(keysym)
                on = true
                if (allowed()) engine.keyEvent(keysym, true)
            }
        }
        onModifiersChanged?.invoke()
        return on
    }

    /** 释放全部粘滞修饰键 */
    fun releaseModifiers() {
        synchronized(heldModifiers) {
            if (heldModifiers.isNotEmpty()) {
                if (allowed()) {
                    heldModifiers.toList().reversed().forEach { engine.keyEvent(it, false) }
                }
                heldModifiers.clear()
            }
        }
        onModifiersChanged?.invoke()
    }

    // ------------------------------------------------------------------
    // 键盘
    // ------------------------------------------------------------------

    /** 单键按下/抬起（硬键盘路径） */
    fun key(keysym: Int, down: Boolean) {
        if (!allowed()) return
        if (keysym in MODIFIER_SET) {
            // 硬键盘修饰键直接透传，不进粘滞集合
            engine.keyEvent(keysym, down)
            return
        }
        if (down) {
            // 粘滞修饰键已就位，主键直接发
            engine.keyEvent(keysym, true)
        } else {
            engine.keyEvent(keysym, false)
            // 主键抬起后清空粘滞修饰键（组合完成）
            releaseModifiersAfterChord()
        }
    }

    /** 组合键（虚拟键盘主键路径）：粘滞修饰 → 主键点按 → 修饰逆序释放 */
    fun tapKey(keysym: Int) {
        if (!allowed()) return
        val mods = synchronized(heldModifiers) { heldModifiers.toList() }
        mods.forEach { engine.keyEvent(it, true) }
        engine.keyEvent(keysym, true)
        engine.keyEvent(keysym, false)
        mods.reversed().forEach { engine.keyEvent(it, false) }
        synchronized(heldModifiers) { heldModifiers.clear() }
        onModifiersChanged?.invoke()
    }

    private fun releaseModifiersAfterChord() {
        synchronized(heldModifiers) {
            if (heldModifiers.isNotEmpty()) {
                heldModifiers.toList().reversed().forEach { engine.keyEvent(it, false) }
                heldModifiers.clear()
            }
        }
        onModifiersChanged?.invoke()
    }

    /** 释放远端全部按键（失焦/切后台时调用，防按键卡死） */
    fun releaseAll() {
        if (allowed()) engine.releaseAllKeys()
        synchronized(heldModifiers) { heldModifiers.clear() }
        onModifiersChanged?.invoke()
    }

    // ------------------------------------------------------------------
    // 鼠标
    // ------------------------------------------------------------------

    /** 绝对坐标移动（直接触摸模式） */
    fun mouseAbsolute(x: Int, y: Int, buttonMask: Int) {
        if (!allowed()) return
        engine.mouseAbsolute(x, y, buttonMask)
    }

    /** 相对移动（触摸板模式） */
    fun mouseRelative(dx: Int, dy: Int, buttonMask: Int) {
        if (!allowed()) return
        engine.mouseRelative(dx, dy, buttonMask)
    }

    /** 滚轮（X11 兼容路径：m2 位脉冲） */
    fun scrollNotches(dxNotches: Int, dyNotches: Int) {
        if (!allowed()) return
        var dy = dyNotches
        while (dy != 0) {
            val mag = if (dy > 0) 1 else -1
            engine.wheelPulse(if (dy > 0) BIT_SCROLL_DOWN else BIT_SCROLL_UP, mag)
            dy -= mag
        }
        var dx = dxNotches
        while (dx != 0) {
            val mag = if (dx > 0) 1 else -1
            engine.wheelPulse(if (dx > 0) BIT_SCROLL_RIGHT else BIT_SCROLL_LEFT, mag)
            dx -= mag
        }
    }

    /** 平滑滚动（Wayland finger_scroll 路径）：流像素 */
    fun fingerScroll(dx: Float, dy: Float) {
        if (!allowed()) return
        engine.fingerScroll(dx, dy)
    }

    fun fingerScrollEnd() {
        if (!allowed()) return
        engine.fingerScrollEnd()
    }

    /** 提交文本（文字输入框） */
    fun commitText(text: String) {
        if (!allowed() || text.isEmpty()) return
        engine.commitText(text)
    }

    companion object {
        // RFB 按钮位（官方 input.js 约定）
        const val BUTTON_LEFT = 0
        const val BUTTON_MIDDLE = 1
        const val BUTTON_RIGHT = 2
        const val BIT_SCROLL_UP = 3
        const val BIT_SCROLL_DOWN = 4
        const val BIT_SCROLL_LEFT = 6
        const val BIT_SCROLL_RIGHT = 7

        fun maskOf(vararg buttons: Int): Int {
            var m = 0
            buttons.forEach { m = m or (1 shl it) }
            return m
        }

        private val MODIFIER_SET = setOf(
            Keysyms.CTRL_L, Keysyms.CTRL_R, Keysyms.ALT_L, Keysyms.ALT_R,
            Keysyms.SHIFT_L, Keysyms.SHIFT_R, Keysyms.SUPER_L, Keysyms.SUPER_R,
            Keysyms.META_L, Keysyms.META_R
        )
    }
}
