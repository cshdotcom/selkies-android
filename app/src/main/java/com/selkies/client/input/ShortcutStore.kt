package com.selkies.client.input

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 快捷键存储 —— 预置常用组合 + 用户自定义（可增删，永久保存）。
 *
 * 数据模型：modifiers（X11 keysym 列表）+ 主键 keysym。
 * 触发即模拟：修饰键按下 → 主键按下/抬起 → 修饰键逆序抬起（官方 chord 行为）。
 *
 * 自定义入口（ShortcutsActivity）：
 *  A. 软键盘组合添加：点亮 Ctrl/Alt/Shift/Super + 点选主键 → 保存；
 *  B. 物理键盘直接录制：插入有线/蓝牙键盘，按下任意组合键即捕获为快捷键。
 */
class ShortcutStore(context: Context) {

    data class Shortcut(
        val id: String,
        val name: String,
        val modifiers: List<Int>,
        val key: Int,
        val builtin: Boolean = false
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    /** 全部快捷键：预置 + 自定义（预置在前） */
    fun all(): List<Shortcut> = BUILTINS + custom()

    /** 用户自定义 */
    fun custom(): List<Shortcut> {
        val raw = prefs.getString("custom", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val mods = o.optJSONArray("m")?.let { m ->
                    (0 until m.length()).map { j -> m.optInt(j) }
                } ?: emptyList()
                Shortcut(
                    id = o.optString("id"),
                    name = o.optString("n"),
                    modifiers = mods,
                    key = o.optInt("k"),
                    builtin = false
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(name: String, modifiers: List<Int>, key: Int) {
        val list = custom().toMutableList()
        list.add(
            Shortcut(
                id = "u${System.currentTimeMillis()}",
                name = name.ifEmpty { describe(modifiers, key) },
                modifiers = modifiers,
                key = key
            )
        )
        saveCustom(list)
    }

    fun remove(id: String) {
        saveCustom(custom().filter { it.id != id })
    }

    private fun saveCustom(list: List<Shortcut>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("n", s.name)
                    .put("m", JSONArray(s.modifiers))
                    .put("k", s.key)
            )
        }
        prefs.edit().putString("custom", arr.toString()).apply()
    }

    companion object {
        /** 人类可读名称：Ctrl+Alt+T 形态 */
        fun describe(modifiers: List<Int>, key: Int): String {
            val sb = StringBuilder()
            // 固定顺序显示
            if (Keysyms.CTRL_L in modifiers || Keysyms.CTRL_R in modifiers) sb.append("Ctrl+")
            if (Keysyms.ALT_L in modifiers || Keysyms.ALT_R in modifiers) sb.append("Alt+")
            if (Keysyms.SHIFT_L in modifiers || Keysyms.SHIFT_R in modifiers) sb.append("Shift+")
            if (Keysyms.SUPER_L in modifiers || Keysyms.SUPER_R in modifiers) sb.append("Super+")
            sb.append(Keysyms.nameOf(key) ?: "0x${key.toString(16)}")
            return sb.toString()
        }

        /** 预置：覆盖桌面 / 浏览器 / 终端 / 系统最常用组合 */
        private fun sc(id: String, name: String, mods: List<Int>, key: Int) =
            Shortcut(id, name, mods, key, builtin = true)

        private val C = Keysyms.CTRL_L
        private val A = Keysyms.ALT_L
        private val S = Keysyms.SHIFT_L

        private val BUILTINS = listOf(
            sc("b_cc", "Ctrl+C", listOf(C), 'c'.code),
            sc("b_cv", "Ctrl+V", listOf(C), 'v'.code),
            sc("b_cx", "Ctrl+X", listOf(C), 'x'.code),
            sc("b_cz", "Ctrl+Z", listOf(C), 'z'.code),
            sc("b_csz", "Ctrl+Shift+Z", listOf(C, S), 'z'.code),
            sc("b_ca", "Ctrl+A", listOf(C), 'a'.code),
            sc("b_cs", "Ctrl+S", listOf(C), 's'.code),
            sc("b_cf", "Ctrl+F", listOf(C), 'f'.code),
            sc("b_cp", "Ctrl+P 打印", listOf(C), 'p'.code),
            sc("b_at", "Alt+Tab", listOf(A), Keysyms.TAB),
            sc("b_ae", "Alt+F4", listOf(A), Keysyms.F4),
            sc("b_cat", "Ctrl+Alt+T 终端", listOf(C, A), 't'.code),
            sc("b_cal", "Ctrl+Alt+L 锁屏", listOf(C, A), 'l'.code),
            sc("b_cad", "Ctrl+Alt+Del", listOf(C, Keysyms.ALT_R, Keysyms.DELETE), Keysyms.DELETE),
            sc("b_cse", "Ctrl+Shift+Esc", listOf(C, S), Keysyms.ESCAPE),
            sc("b_cn", "Ctrl+N", listOf(C), 'n'.code),
            sc("b_ct", "Ctrl+T", listOf(C), 't'.code),
            sc("b_cw", "Ctrl+W", listOf(C), 'w'.code),
            sc("b_cl", "Ctrl+L", listOf(C), 'l'.code),
            sc("b_cr", "Ctrl+R", listOf(C), 'r'.code),
            sc("b_cq", "Ctrl+Q", listOf(C), 'q'.code),
            sc("b_csn", "Ctrl+Shift+N", listOf(C, S), 'n'.code),
            sc("b_ch", "Ctrl+Home", listOf(C), Keysyms.HOME),
            sc("b_ce", "Ctrl+End", listOf(C), Keysyms.END),
            sc("b_ctab", "Ctrl+Tab", listOf(C), Keysyms.TAB)
        )
    }
}
