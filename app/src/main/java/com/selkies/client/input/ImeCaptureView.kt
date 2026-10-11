package com.selkies.client.input

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * IME 直通捕获层 —— 对齐官方网页前端的 hidden-textarea 输入模型。
 *
 * 设计目标（用户核心诉求）：
 *  - 点键盘按钮直接唤起系统输入法，打字**实时**发到远端（每次 IME 提交词组即刻
 *    `co,end,<text>`），不需要按"发送"；
 *  - **回车**以按键事件发送（kd/ku 0xff0d），远端收到的是真正的 Enter 键，
 *    能正常触发确认/换行/提交 —— 这正是"文本框+发送按钮"模式做不到的；
 *  - 退格、方向等编辑键同样走按键事件，与远端桌面行为一致。
 *
 * 实现原理：
 *  - 本 View 本身零渲染（透明、0x0 尺寸也可，保留 1px 保证可聚焦）；
 *  - onCheckIsTextEditor()=true + onCreateInputConnection() 返回拦截式
 *    InputConnection：commitText → 拆行（\n 转回车键事件）→ co,end；
 *    deleteSurroundingText → BackSpace 键事件；sendKeyEvent → 完整键事件；
 *  - setComposingText 被吞（组合中的拼音不上屏，与官方行为一致：
 *    只有最终 commit 的文本才发往远端）；
 *  - 物理键盘事件不经过本层（Activity.dispatchKeyEvent 已直通），
 *    软硬键盘互不干扰。
 */
class ImeCaptureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : android.view.View(context, attrs, defStyleAttr) {

    /** IME 提交文本 → co,end（多行文本已拆分为多次回调，不含 '\n'） */
    var onTextCommit: ((String) -> Unit)? = null

    /** 键事件（回车/退格/IME 直发键）→ kd/ku keysym */
    var onKey: ((keyCode: Int, action: Int, event: KeyEvent?) -> Boolean)? = null

    /** 输入法连接建立（软键盘弹出） */
    var onImeOpened: (() -> Unit)? = null

    /** 输入法关闭 */
    var onImeClosed: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // 尺寸由布局 XML 控制（1dp 透明），确保可聚焦与弹 IME 但视觉不可见
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // text 多行（无 IME_FLAG_FORCE_SINGLE_LINE），让回车以 KEYCODE_ENTER 键事件送达；
        // NO_EXTRACT_UI/NO_FULLSCREEN：横屏不进全屏编辑态，画面可见
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return InterceptingConnection(this)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) onImeClosed?.invoke()
    }

    /**
     * 拦截式 InputConnection：所有编辑动作翻译为 Selkies 协议事件。
     * fullEditor=false：文本状态由 IME 自持，本层不维护编辑缓冲
     * （远端桌面才是"真正的编辑框"）。
     */
    private inner class InterceptingConnection(target: ImeCaptureView) :
        BaseInputConnection(target, false) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val s = text.toString()
            if (s.isEmpty()) return true
            // 按行拆分：'\n' 转换为真正的回车键事件（远端 Enter）
            val lines = s.split('\n')
            lines.forEachIndexed { i, line ->
                if (line.isNotEmpty()) onTextCommit?.invoke(line)
                if (i < lines.size - 1) sendKeyAsEnter()
            }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            // 组合态文本（拼音候选过程）不上屏 —— 与官方网页行为一致
            return true
        }

        override fun finishComposingText(): Boolean = true

        override fun setComposingRegion(start: Int, end: Int): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // 退格：N 个 BackSpace 键事件（Down+Up 成对）
            repeat(beforeLength.coerceIn(0, 64)) { sendKeyAsBackspace() }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int, afterLength: Int
        ): Boolean = deleteSurroundingText(beforeLength, afterLength)

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            // IME 直接投递的物理键（多数输入法的回车 / 硬件回车）→ 键事件直通
            onKey?.invoke(event.keyCode, event.action, event)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            // 少数 IME 用 action 回车：IME_ACTION_NONE 时一般不走这里，兜底处理
            if (actionCode == EditorInfo.IME_ACTION_NONE ||
                actionCode == EditorInfo.IME_ACTION_UNSPECIFIED ||
                actionCode == EditorInfo.IME_ACTION_SEND ||
                actionCode == EditorInfo.IME_ACTION_DONE ||
                actionCode == EditorInfo.IME_ACTION_GO ||
                actionCode == EditorInfo.IME_ACTION_SEARCH ||
                actionCode == EditorInfo.IME_ACTION_NEXT
            ) {
                sendKeyAsEnter()
                return true
            }
            return false
        }

        override fun performContextMenuAction(id: Int): Boolean = true

        private fun sendKeyAsEnter() {
            val now = android.os.SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
            val up = KeyEvent(now, now + 16, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)
            onKey?.invoke(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, down)
            onKey?.invoke(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, up)
        }

        private fun sendKeyAsBackspace() {
            val now = android.os.SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0)
            val up = KeyEvent(now, now + 16, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0)
            onKey?.invoke(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_DOWN, down)
            onKey?.invoke(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP, up)
        }
    }
}
