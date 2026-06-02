package com.sniray.app.v2ray.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.sniray.app.R
import com.sniray.app.data.AppDatabase
import com.sniray.app.data.ProxyConfigEntity
import com.sniray.app.data.ProxyRepository
import com.sniray.app.databinding.ActivityRstaBypassEditBinding
import com.sniray.app.v2ray.extension.toast
import com.sniray.app.v2ray.extension.toastSuccess
import com.sniray.app.v2ray.util.RstaBypassValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RstaBypassEditActivity : BaseActivity() {

    private val binding by lazy { ActivityRstaBypassEditBinding.inflate(layoutInflater) }
    private val repository by lazy {
        ProxyRepository(AppDatabase.get(this).proxyConfigDao())
    }

    private val configId by lazy { intent.getLongExtra(EXTRA_CONFIG_ID, 0L) }
    private var loadedConfig: ProxyConfigEntity? = null
    private var delItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val titleRes = if (configId == 0L) R.string.title_rsta_bypass_new else R.string.title_rsta_bypass_edit
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(titleRes))

        val methods = resources.getStringArray(R.array.rsta_bypass_methods)
        binding.spinnerMethod.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            methods,
        )

        lifecycleScope.launch {
            loadedConfig = if (configId == 0L) null else withContext(Dispatchers.IO) {
                repository.getById(configId)
            }
            bindForm(loadedConfig)
        }
    }

    private fun bindForm(config: ProxyConfigEntity?) {
        binding.etName.setText(config?.name ?: "")
        binding.etListenHost.setText(config?.listenHost ?: "0.0.0.0")
        binding.etListenPort.setText(config?.listenPort?.toString() ?: "40443")
        binding.etConnectHost.setText(config?.connectHost ?: "104.19.229.21")
        binding.etConnectPort.setText(config?.connectPort?.toString() ?: "443")
        binding.etFakeSni.setText(config?.fakeSni ?: "www.hcaptcha.com")
        val methods = resources.getStringArray(R.array.rsta_bypass_methods)
        val method = config?.method ?: "combined"
        val index = methods.indexOf(method).coerceAtLeast(0)
        binding.spinnerMethod.setSelection(index)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        delItem = menu.findItem(R.id.del_config)
        delItem?.isVisible = configId != 0L
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.save_config -> {
            saveConfig()
            true
        }
        R.id.del_config -> {
            deleteConfig()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun saveConfig() {
        val name = binding.etName.text?.toString().orEmpty()
        val listenHost = binding.etListenHost.text?.toString().orEmpty()
        val listenPort = binding.etListenPort.text?.toString().orEmpty()
        val connectHost = binding.etConnectHost.text?.toString().orEmpty()
        val connectPort = binding.etConnectPort.text?.toString().orEmpty()
        val fakeSni = binding.etFakeSni.text?.toString().orEmpty()
        val method = binding.spinnerMethod.selectedItem?.toString().orEmpty()

        val error = RstaBypassValidator.validate(
            this, name, listenHost, listenPort, connectHost, connectPort, fakeSni, method,
        )
        if (error != null) {
            binding.tvError.text = error
            binding.tvError.visibility = View.VISIBLE
            return
        }
        binding.tvError.visibility = View.GONE

        val entity = ProxyConfigEntity(
            id = loadedConfig?.id ?: 0L,
            name = name.trim(),
            listenHost = listenHost.trim(),
            listenPort = listenPort.toInt(),
            connectHost = connectHost.trim(),
            connectPort = connectPort.toInt(),
            fakeSni = fakeSni.trim(),
            method = method,
            lastUsedAt = loadedConfig?.lastUsedAt ?: 0,
        )

        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) { repository.save(entity) }
            setResult(
                RESULT_OK,
                intent.putExtra(EXTRA_CONFIG_ID, id),
            )
            toastSuccess(R.string.menu_item_save_config)
            finish()
        }
    }

    private fun deleteConfig() {
        val config = loadedConfig ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.delete(config) }
            toastSuccess(R.string.menu_item_del_config)
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val EXTRA_CONFIG_ID = "config_id"
    }
}
