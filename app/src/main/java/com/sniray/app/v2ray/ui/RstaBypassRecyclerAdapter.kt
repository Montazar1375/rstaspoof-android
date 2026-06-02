package com.sniray.app.v2ray.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sniray.app.R
import com.sniray.app.data.ProxyConfigEntity
import com.sniray.app.databinding.ItemRstaBypassConfigBinding

class RstaBypassRecyclerAdapter(
    private val onSelect: (Long) -> Unit,
    private val onEdit: (Long) -> Unit,
    private val onDelete: (ProxyConfigEntity) -> Unit,
) : RecyclerView.Adapter<RstaBypassRecyclerAdapter.ViewHolder>() {

    private var configs: List<ProxyConfigEntity> = emptyList()
    private var selectedId: Long? = null
    private var selectionLocked = false

    fun submit(configs: List<ProxyConfigEntity>, selectedId: Long?, selectionLocked: Boolean) {
        this.configs = configs
        this.selectedId = selectedId
        this.selectionLocked = selectionLocked
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = configs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRstaBypassConfigBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(configs[position])
    }

    inner class ViewHolder(
        private val binding: ItemRstaBypassConfigBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(config: ProxyConfigEntity) {
            val selected = config.id == selectedId
            val canInteract = !selectionLocked

            binding.tvName.text = config.name
            binding.tvListen.text = binding.root.context.getString(
                R.string.rsta_bypass_listen_format,
                config.listenHost,
                config.listenPort,
            )
            binding.tvConnect.text = binding.root.context.getString(
                R.string.rsta_bypass_connect_format,
                config.connectHost,
                config.connectPort,
                config.fakeSni,
            )
            binding.tvMethod.text = binding.root.context.getString(
                R.string.rsta_bypass_method_format,
                config.method,
            )

            binding.radioSelected.isChecked = selected
            binding.radioSelected.isEnabled = canInteract
            binding.btnEdit.isEnabled = canInteract
            binding.btnDelete.isEnabled = canInteract

            val ctx = binding.root.context
            val containerColor = if (selected) {
                ContextCompat.getColor(ctx, R.color.md_theme_primaryContainer)
            } else {
                ContextCompat.getColor(ctx, R.color.md_theme_surfaceVariant)
            }
            binding.cardConfig.setCardBackgroundColor(containerColor)
            binding.root.alpha = if (selectionLocked && !selected) 0.55f else 1f

            binding.layoutInfo.setOnClickListener {
                if (canInteract) onSelect(config.id)
            }
            binding.radioSelected.setOnClickListener {
                if (canInteract) onSelect(config.id)
            }
            binding.btnEdit.setOnClickListener { onEdit(config.id) }
            binding.btnDelete.setOnClickListener { onDelete(config) }
        }
    }
}
