package com.sniray.app.v2ray.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.sniray.app.databinding.FragmentRstaLogsBinding
import com.sniray.app.vm.ProxyViewModel
import com.sniray.app.v2ray.util.TerminalColors
import kotlinx.coroutines.launch

class RstaLogsFragment : BaseFragment<FragmentRstaLogsBinding>() {

    private val viewModel: ProxyViewModel by activityViewModels()
    private lateinit var adapter: RstaLogsRecyclerAdapter
    private var autoScroll = true

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentRstaLogsBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        TerminalColors.init(requireContext())
        viewModel.refreshLogsFromServiceState()

        adapter = RstaLogsRecyclerAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.switchAutoScroll.isChecked = autoScroll
        binding.switchAutoScroll.setOnCheckedChangeListener { _, checked ->
            autoScroll = checked
            if (autoScroll && viewModel.logs.value.isNotEmpty()) {
                scrollToEnd()
            }
        }

        applyLogs(viewModel.logs.value)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.logs.collect { logs -> applyLogs(logs) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLogsFromServiceState()
        applyLogs(viewModel.logs.value)
    }

    private fun applyLogs(logs: List<String>) {
        adapter.submit(logs)
        val hasLogs = logs.isNotEmpty()
        binding.tvEmpty.visibility = if (hasLogs) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = if (hasLogs) View.VISIBLE else View.GONE
        if (autoScroll && hasLogs) {
            scrollToEnd()
        }
    }

    private fun scrollToEnd() {
        val count = adapter.itemCount
        if (count <= 0) return
        val recyclerView = binding.recyclerView
        val target = count - 1
        recyclerView.post {
            if (!isAdded || view == null || !recyclerView.isAttachedToWindow) return@post
            recyclerView.scrollToPosition(target)
        }
    }
}
