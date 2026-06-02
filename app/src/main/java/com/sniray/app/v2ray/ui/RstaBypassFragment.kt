package com.sniray.app.v2ray.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.sniray.app.R
import com.sniray.app.data.ProxyConfigEntity
import com.sniray.app.databinding.FragmentRstaBypassBinding
import com.sniray.app.service.ProxyRunState
import com.sniray.app.vm.ProxyViewModel
import kotlinx.coroutines.launch

class RstaBypassFragment : BaseFragment<FragmentRstaBypassBinding>() {

    private val viewModel: ProxyViewModel by activityViewModels()
    private lateinit var adapter: RstaBypassRecyclerAdapter

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getLongExtra(RstaBypassEditActivity.EXTRA_CONFIG_ID, -1L) ?: -1L
            if (id >= 0) viewModel.selectConfig(id)
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentRstaBypassBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RstaBypassRecyclerAdapter(
            onSelect = { viewModel.selectConfig(it) },
            onEdit = { openEdit(it) },
            onDelete = { confirmDelete(it) },
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        addCustomDividerToRecyclerView(binding.recyclerView, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        // @id/switch_bypass_chain — restart VPN via MainActivity when toggled while connected
        binding.switchBypassChain.setChecked(viewModel.bypassChainEnabled.value)
        binding.switchBypassChain.setOnCheckedChangeListener { _, isChecked ->
            onSwitchBypassChainChanged(isChecked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.configs.collect { configs ->
                        val selectedId = viewModel.selectedId.value
                        if (selectedId == null && configs.isNotEmpty()) {
                            viewModel.selectConfig(configs.first().id)
                        }
                        refreshList()
                    }
                }
                launch {
                    viewModel.selectedId.collect { refreshList() }
                }
                launch {
                    viewModel.bypassChainEnabled.collect { enabled ->
                        binding.switchBypassChain.setOnCheckedChangeListener(null)
                        binding.switchBypassChain.setChecked(enabled)
                        binding.switchBypassChain.setOnCheckedChangeListener { _, isChecked ->
                            onSwitchBypassChainChanged(isChecked)
                        }
                    }
                }
                launch {
                    viewModel.runState.collect { state ->
                        val locked = state is ProxyRunState.Running || state is ProxyRunState.Starting
                        binding.tvSelectionLocked.visibility =
                            if (locked) View.VISIBLE else View.GONE
                        refreshList()
                    }
                }
            }
        }
    }

    private fun onSwitchBypassChainChanged(enabled: Boolean) {
        val main = requireActivity()
        if (main !is MainActivity) return
        main.onBypassChainSwitchChanged(enabled)
    }

    private fun refreshList() {
        val locked = viewModel.runState.value is ProxyRunState.Running ||
            viewModel.runState.value is ProxyRunState.Starting
        adapter.submit(
            configs = viewModel.configs.value,
            selectedId = viewModel.selectedId.value,
            selectionLocked = locked,
        )
    }

    private fun openEdit(id: Long) {
        editLauncher.launch(
            Intent(requireContext(), RstaBypassEditActivity::class.java)
                .putExtra(RstaBypassEditActivity.EXTRA_CONFIG_ID, id),
        )
    }

    private fun confirmDelete(config: ProxyConfigEntity) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.rsta_bypass_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.deleteConfig(config)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
