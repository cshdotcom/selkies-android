package com.selkies.client.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.selkies.client.R
import com.selkies.client.input.Keysyms
import com.selkies.client.input.ShortcutStore

/**
 * 快捷键管理页 —— 用户核心诉求：
 *  1. 增加所有常用快捷键（预置 24 条）+ 手机端发不出的组合（Ctrl+Alt+T 等）；
 *  2. 快捷键可自己添加；
 *  3. 【软键盘组合添加】点亮 Ctrl/Alt/Shift/Super + 从键位表点选主键；
 *  4. 【物理键盘直接录制】插入有线/蓝牙键盘后，直接按下热键或单键即捕获为快捷键。
 */
class ShortcutsActivity : AppCompatActivity() {

    private lateinit var store: ShortcutStore
    private lateinit var adapter: ShortcutAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcuts)

        store = ShortcutStore(this)
        adapter = ShortcutAdapter(store)

        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@ShortcutsActivity)
            adapter = this@ShortcutsActivity.adapter
        }
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_add).setOnClickListener { showAddDialog() }

        reload()
    }

    private fun reload() {
        adapter.submit(store.all())
    }

    // ------------------------------------------------------------------
    // 添加对话框：两种录入方式
    // ------------------------------------------------------------------

    private class Draft {
        var name = ""
        var modifiers = mutableSetOf(Keysyms.CTRL_L)
        var key: Int? = null
    }

    private fun showAddDialog() {
        val draft = Draft()
        val view = layoutInflater.inflate(R.layout.dialog_shortcut_add, null)
        val editName = view.findViewById<EditText>(R.id.edit_name)
        val txtKey = view.findViewById<TextView>(R.id.txt_key)
        val txtRecorded = view.findViewById<TextView>(R.id.txt_recorded)

        // 修饰键开关（软键盘组合方式）
        val modButtons = mapOf(
            R.id.mod_ctrl to Keysyms.CTRL_L,
            R.id.mod_alt to Keysyms.ALT_L,
            R.id.mod_shift to Keysyms.SHIFT_L,
            R.id.mod_super to Keysyms.SUPER_L
        )
        fun refreshMods() {
            modButtons.forEach { (id, ks) ->
                view.findViewById<TextView>(id)?.isSelected = ks in draft.modifiers
            }
            txtKey.text = draft.key?.let {
                ShortcutStore.describe(draft.modifiers.toList(), it)
            } ?: getString(R.string.shortcut_pick_key)
        }
        modButtons.forEach { (id, ks) ->
            view.findViewById<TextView>(id)?.setOnClickListener {
                if (ks in draft.modifiers) draft.modifiers.remove(ks) else draft.modifiers.add(ks)
                refreshMods()
            }
        }

        // 主键选择（键位表）
        view.findViewById<View>(R.id.btn_pick_key).setOnClickListener {
            showKeyPicker { keysym ->
                draft.key = keysym
                if (editName.text.isNullOrBlank()) {
                    editName.setText(ShortcutStore.describe(draft.modifiers.toList(), keysym))
                }
                refreshMods()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.shortcut_add)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val key = draft.key
                if (key == null) {
                    Toast.makeText(this, R.string.shortcut_need_key, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.add(editName.text.toString(), draft.modifiers.toList(), key)
                reload()
                Toast.makeText(this, R.string.shortcut_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()

        // 【物理键盘录制】：对话框期间按下物理键盘组合键 → 直接捕获
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (isSystemKeyCode(keyCode)) return@setOnKeyListener false
            val keysym = Keysyms.fromAndroidKeyCode(keyCode, event) ?: return@setOnKeyListener false
            if (Keysyms.isModifierKeysym(keysym)) {
                // 修饰键：实时切换开关（录制反馈）
                if (keysym in draft.modifiers) draft.modifiers.remove(keysym)
                else draft.modifiers.add(keysym)
                refreshMods()
                return@setOnKeyListener true
            }
            // 主键：连同 metaState 修饰一起捕获
            draft.modifiers = Keysyms.modifiersFromMetaState(event.metaState).toMutableSet()
            if (draft.modifiers.isEmpty()) draft.modifiers.add(Keysyms.CTRL_L) // 默认给 Ctrl
            draft.key = keysym
            if (event.repeatCount == 0) {
                txtRecorded.text = getString(
                    R.string.shortcut_recorded,
                    ShortcutStore.describe(draft.modifiers.toList(), keysym)
                )
                txtRecorded.visibility = View.VISIBLE
                if (editName.text.isNullOrBlank()) {
                    editName.setText(ShortcutStore.describe(draft.modifiers.toList(), keysym))
                }
            }
            refreshMods()
            true
        }
        refreshMods()
    }

    /** 键位选择表：A–Z / 0–9 / F1–F12 / 编辑导航键 / 符号（手机端发不出的全量键位） */
    private fun showKeyPicker(onPick: (Int) -> Unit) {
        val entries = ArrayList<String>()
        val keysyms = ArrayList<Int>()

        fun add(label: String, ks: Int) {
            entries.add(label); keysyms.add(ks)
        }

        ('a'..'z').forEach { add(it.uppercaseChar().toString(), it.code) }
        ('0'..'9').forEach { add(it.toString(), it.code) }
        for (i in 1..12) add("F$i", Keysyms.F1 + (i - 1))
        add("Enter", Keysyms.RETURN); add("Tab", Keysyms.TAB)
        add("Esc", Keysyms.ESCAPE); add("Space", Keysyms.SPACE)
        add("Backspace", Keysyms.BACKSPACE); add("Delete", Keysyms.DELETE)
        add("Insert", Keysyms.INSERT); add("Home", Keysyms.HOME)
        add("End", Keysyms.END); add("PgUp", Keysyms.PAGE_UP)
        add("PgDn", Keysyms.PAGE_DOWN)
        add("←", Keysyms.LEFT); add("↑", Keysyms.UP)
        add("↓", Keysyms.DOWN); add("→", Keysyms.RIGHT)
        add("Print Screen", Keysyms.PRINT); add("Pause", Keysyms.PAUSE)
        add("Scroll Lock", Keysyms.SCROLL_LOCK); add("Menu", Keysyms.MENU)
        add("/", '/'.code); add("\\", '\\'.code)
        add("[", '['.code); add("]", ']'.code)
        add(";", ';'.code); add("'", '\''.code)
        add(",", ','.code); add(".", '.'.code); add("-", '-'.code)
        add("=", '='.code); add("`", '`'.code)

        AlertDialog.Builder(this)
            .setTitle(R.string.shortcut_pick_key)
            .setItems(entries.toTypedArray()) { _, which ->
                onPick(keysyms[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/** 系统键判断（录制时跳过：返回/音量等不进快捷键） */
private fun isSystemKeyCode(keyCode: Int): Boolean = keyCode in setOf(
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_MENU,
    KeyEvent.KEYCODE_HOME,
    KeyEvent.KEYCODE_APP_SWITCH,
    KeyEvent.KEYCODE_NOTIFICATION
)

/** 快捷键列表适配器 */
class ShortcutAdapter(private val store: ShortcutStore) :
    RecyclerView.Adapter<ShortcutAdapter.VH>() {

    private val items = mutableListOf<ShortcutStore.Shortcut>()

    fun submit(list: List<ShortcutStore.Shortcut>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.txt_name)
        val combo: TextView = v.findViewById(R.id.txt_combo)
        val builtin: TextView = v.findViewById(R.id.txt_builtin)
        val delete: ImageButton = v.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_shortcut, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.name.text = s.name
        holder.combo.text = ShortcutStore.describe(s.modifiers, s.key)
        holder.builtin.visibility = if (s.builtin) View.VISIBLE else View.GONE
        holder.delete.visibility = if (s.builtin) View.GONE else View.VISIBLE
        holder.delete.setOnClickListener {
            store.remove(s.id)
            submit(store.all())
        }
    }
}
