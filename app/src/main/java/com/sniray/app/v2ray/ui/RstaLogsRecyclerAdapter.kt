package com.sniray.app.v2ray.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sniray.app.databinding.ItemRstaLogLineBinding
import com.sniray.app.v2ray.util.AnsiParser
import com.sniray.app.v2ray.util.TerminalColors
import com.sniray.app.v2ray.util.Utils

class RstaLogsRecyclerAdapter(
    private val onLongClick: ((String) -> Boolean)? = null,
) : RecyclerView.Adapter<RstaLogsRecyclerAdapter.ViewHolder>() {

    private var lines: List<String> = emptyList()

    fun submit(lines: List<String>) {
        this.lines = lines
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRstaLogLineBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(lines[position])
    }

    inner class ViewHolder(
        private val binding: ItemRstaLogLineBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(line: String) {
            binding.tvLine.text = AnsiParser.parse(line, TerminalColors.foreground)
            binding.root.setOnLongClickListener {
                Utils.setClipboard(binding.root.context, line)
                onLongClick?.invoke(line) ?: true
            }
        }
    }
}
