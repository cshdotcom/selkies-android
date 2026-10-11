package com.selkies.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.selkies.client.R
import com.selkies.client.data.ServerConfig

/**
 * 服务器列表适配器。
 */
class ServerAdapter(
    private val onClick: (ServerConfig) -> Unit,
    private val onLongClick: (ServerConfig, View) -> Unit,
    private val onMoreClick: (ServerConfig, View) -> Unit
) : RecyclerView.Adapter<ServerAdapter.VH>() {

    private val items = mutableListOf<ServerConfig>()

    fun submit(list: List<ServerConfig>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.txt_name)
        val address: TextView = v.findViewById(R.id.txt_address)
        val mode: TextView = v.findViewById(R.id.txt_mode)
        val star: ImageView = v.findViewById(R.id.img_default)
        val more: View = v.findViewById(R.id.btn_more)
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
        holder.itemView.setOnClickListener { onClick(cfg) }
        holder.itemView.setOnLongClickListener {
            onLongClick(cfg, it)
            true
        }
        holder.more.setOnClickListener { onMoreClick(cfg, it) }
    }
}
