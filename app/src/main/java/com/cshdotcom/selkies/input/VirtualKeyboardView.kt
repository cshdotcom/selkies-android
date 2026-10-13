package com.cshdotcom.selkies.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.cshdotcom.selkies.connection.Keysyms
import com.cshdotcom.selkies.connection.SelkiesClient

/**
 * 虚拟键盘面板（底部）：
 * - 修饰键行：Ctrl/Alt/Shift/Win 粘性切换（kd … ku）
 * - 符号键行：ESC TAB DEL ←↑↓→ HOME END PGUP PGDN INS PRTSC
 * - 功能键行（横向滚动）：F1..F12
 * - 文本输入行：EditText 接入系统 IME，提交文本以 co,end 直投远端（中文输入法可用），
 *   退格映射 BackSpace keysym
 */
class VirtualKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var client: SelkiesClient? = null

    private val stickyKeys = mutableMapOf<Int, Boolean>()   // keysym -> pressed
    private val modifierButtons = mutableMapOf<Int, Button>()

    private val mods = listOf(
        "Ctrl" to Keysyms.Control_L, "Alt" to Keysyms.Alt_L,
        "Shift" to Keysyms.Shift_L, "Win" to Keysyms.Super_L,
        "AltGr" to Keysyms.Alt_Gr
    )

    private val symbolKeys = listOf(
        "Esc" to Keysyms.Escape, "Tab" to Keysyms.Tab, "⌫" to Keysyms.BackSpace,
        "⌦" to Keysyms.Delete, "←" to Keysyms.Left, "↑" to Keysyms.Up,
        "↓" to Keysyms.Down, "→" to Keysyms.Right, "Home" to Keysyms.Home,
        "End" to Keysyms.End, "PgUp" to Keysyms.Page_Up, "PgDn" to Keysyms.Page_Down,
        "Ins" to Keysyms.Insert, "PrtSc" to Keysyms.Print, "Menu" to Keysyms.Menu,
        "NumLk" to Keysyms.Num_Lock, "ScrLk" to Keysyms.Scroll_Lock, "↵" to Keysyms.Return
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(0xEE1B1B1F.toInt())
        val pad = dp(4)
        setPadding(pad, pad, pad, pad)
        buildModifierRow()
        buildSymbolRow()
        buildFunctionRow()
        buildTextInput()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun mkButton(label: String): Button = Button(context).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background.alpha = 60
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        lp.setMargins(dp(2), 0, dp(2), 0)
        layoutParams = lp
        minWidth = dp(46)
        minHeight = dp(40)
    }

    private fun buildModifierRow() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        for ((label, sym) in mods) {
            val b = mkButton(label)
            b.setOnClickListener {
                val pressed = stickyKeys[sym] ?: false
                if (pressed) {
                    stickyKeys[sym] = false
                    client?.sendKey(sym, false)
                } else {
                    stickyKeys[sym] = true
                    client?.sendKey(sym, true)
                }
                refreshSticky(b, sym)
            }
            modifierButtons[sym] = b
            row.addView(b)
        }
        // 修饰键全部释放按钮
        val release = mkButton("清修饰").apply {
            setOnClickListener { releaseAllModifiers() }
        }
        row.addView(release)
        addView(row)
    }

    private fun refreshSticky(b: Button, sym: Int) {
        val pressed = stickyKeys[sym] ?: false
        b.setBackgroundColor(if (pressed) 0xFF3D7DFF.toInt() else 0xFF333338.toInt())
    }

    fun releaseAllModifiers() {
        for ((sym, pressed) in stickyKeys) {
            if (pressed) client?.sendKey(sym, false)
            stickyKeys[sym] = false
        }
        for ((sym, b) in modifierButtons) refreshSticky(b, sym)
    }

    private fun buildSymbolRow() {
        val scroll = HorizontalScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for ((label, sym) in symbolKeys) {
            val b = mkButton(label)
            b.setOnClickListener { tapKey(sym) }
            row.addView(b)
        }
        scroll.addView(row)
        addView(scroll)
    }

    private fun buildFunctionRow() {
        val scroll = HorizontalScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (i in 1..12) {
            val b = mkButton("F$i")
            b.setOnClickListener { tapKey(Keysyms.functionKey(i)) }
            row.addView(b)
        }
        // 组合键示例：Ctrl+Alt+Del
        val cad = mkButton("Ctrl+Alt+Del").apply {
            setOnClickListener { ctrlAltDel() }
        }
        row.addView(cad)
        scroll.addView(row)
        addView(scroll)
    }

    private fun tapKey(sym: Int) {
        client?.sendKey(sym, true)
        client?.sendKey(sym, false)
    }

    private fun ctrlAltDel() {
        val c = client ?: return
        listOf(Keysyms.Control_L, Keysyms.Alt_L).forEach { c.sendKey(it, true) }
        c.sendKey(Keysyms.Delete, true); c.sendKey(Keysyms.Delete, false)
        listOf(Keysyms.Control_L, Keysyms.Alt_L).reversed().forEach { c.sendKey(it, false) }
        releaseAllModifiers()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTextInput() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
        }
        val hintLabel = TextView(context).apply {
            text = "文本直投："
            setTextColor(0xFFBBBBBB.toInt())
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setPadding(dp(6), 0, dp(6), 0)
        }
        val input = EditText(context).apply {
            hint = "在此输入并回车发送（支持中文输入法）"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            background.alpha = 40
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        inputField = input
        input.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (ev?.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val text = input.text.toString()
                if (text.isNotEmpty()) { client?.sendText(text); input.setText("") }
                true
            } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 输入过程中退格 → 发 BackSpace
                val n = s?.length ?: 0
                if (n < lastLen) {
                    repeat(lastLen - n) { client?.sendKey(Keysyms.BackSpace, true); client?.sendKey(Keysyms.BackSpace, false) }
                }
                lastLen = n
                if (n > 60) { s?.clear(); lastLen = 0 }   // 防止超长堆积
            }
            private var lastLen = 0
        })
        val send = Button(context).apply {
            text = "发送"
            textSize = 13f
            setOnClickListener {
                val text = input.text.toString()
                if (text.isNotEmpty()) { client?.sendText(text); input.setText("") }
            }
        }
        row.addView(hintLabel); row.addView(input); row.addView(send)
        addView(row)
    }

    /** 断开时清理粘性修饰键（不发送 ku，连接已失效） */
    fun resetLocal() {
        stickyKeys.clear()
        for ((sym, b) in modifierButtons) refreshSticky(b, sym)
    }

    private var inputField: EditText? = null

    /** 聚焦文本输入框并唤起系统输入法 */
    fun focusTextInput() {
        inputField?.let {
            it.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(it, 0)
        }
    }

    override fun onDetachedFromWindow() {
        releaseAllModifiers()
        super.onDetachedFromWindow()
    }
}
