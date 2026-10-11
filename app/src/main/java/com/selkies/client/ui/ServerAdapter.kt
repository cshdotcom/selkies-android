package com.selkies.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.selkies.client.R
import com.selkies.client.data.ServerConfig

/**
 * 服务器列表适配器。
 * 支持两种模式：
 *  - 普通模式：点击连接，长按弹菜单；
 *  - 多选模式（selectMode=true）：显示复选框，点击切换选中，长按无效。
 */
class ServerAdapter(
    private val onClick: (ServerConfig) -> Unit,
    private val onLongClick: (ServerConfig, View) -> Unit,
    private val onMoreClick: (ServerConfig, View) -> Unit
) : RecyclerView.Adapter<ServerAdapter.VH>() {

    private val items = mutableListOf<ServerConfig>()
    private val selected = LinkedHashSet<String>()

    /** 多选模式开关 */
    var selectMode: Boolean = false
        private set

    /** 多选模式下点击条目 → 切换选中 */
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun submit(list: List<ServerConfig>) {
        items.clear()
        items.addAll(list)
        selected.retainAll(items.map { it.id }.toSet())
        notifyDataSetChanged()
    }

    /** 进入/退出多选模式 */
    fun setSelectMode(on: Boolean) {
        selectMode = on
        if (!on) selected.clear()
        notifyDataSetChanged()
    }

    fun selectedIds(): Set<String> = selected.toSet()

    fun selectedCount(): Int = selected.size

    /** 预选某台（进入多选模式时长按的那台） */
    fun preselect(id: String) {
        if (items.any { it.id == id }) {
            selected.add(id)
            notifyDataSetChanged()
            onSelectionChanged?.invoke(selected.size)
        }
    }

    fun selectAll() {
        selected.clear()
        items.forEach { selected.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    private fun toggle(id: String) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        onSelectionChanged?.invoke(selected.size)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.txt_name)
        val address: TextView = v.findViewById(R.id.txt_address)
        val mode: TextView = v.findViewById(R.id.txt_mode)
        val star: ImageView = v.findViewById(R.id.img_default)
        val more: View = v.findViewById(R.id.btn_more)
        val check: CheckBox = v.findViewById(R.id.chk_select)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cfg = items[position]
        holder.name.text = cfg.name.ifEmpty { cfg.host }
        holder.address.text = buildString {
            append(if (cfg.https) "https://" else "http://")
            append(cfg.host)
            if (cfg.port != 80 && cfg.port != 443) append(":").append(cfg.port)
            if (cfg.normalizedPath.isNotEmpty()) append(cfg.normalizedPath)
        }
        holder.mode.text = if (cfg.viewOnly) {
            "👁 " + holder.itemView.context.getString(R.string.status_viewonly)
        } else {
            "⌨ " + holder.itemView.context.getString(R.string.mode_control)
        }
        holder.star.visibility = if (cfg.isDefault) View.VISIBLE else View.GONE

        // 多选模式：显示复选框，隐藏菜单
        holder.check.visibility = if (selectMode) View.VISIBLE else View.GONE
        holder.more.visibility = if (selectMode) View.GONE else View.VISIBLE
        holder.check.isChecked = selected.contains(cfg.id)
        holder.check.isClickable = false // 整行点击处理

        if (selectMode) {
            holder.itemView.setOnClickListener { toggle(cfg.id); notifyItemChanged(position) }
            holder.itemView.setOnLongClickListener(null)
        } else {
            holder.itemView.setOnClickListener { onClick(cfg) }
            holder.itemView.setOnLongClickListener {
                onLongClick(cfg, it)
                true
            }
            holder.more.setOnClickListener { onMoreClick(cfg, it) }
        }
    }
}
