package com.rstaspoof.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.unit.dp
import com.rstaspoof.app.data.ProxyConfigEntity

private val methods = listOf("fragment", "fake_sni", "combined")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigListScreen(
    configs: List<ProxyConfigEntity>,
    selectedId: Long?,
    selectionLocked: Boolean,
    contentPadding: PaddingValues,
    onSelect: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (ProxyConfigEntity) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
            if (selectionLocked) {
                Text(
                    text = "Stop the proxy to switch or edit configs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
            ) {
                items(configs, key = { it.id }) { config ->
                    ConfigCard(
                        config = config,
                        selected = config.id == selectedId,
                        selectionLocked = selectionLocked,
                        onSelect = { onSelect(config.id) },
                        onEdit = { onEdit(config.id) },
                        onDelete = { onDelete(config) },
                    )
                }
            }
        }
}

@Composable
private fun ConfigCard(
    config: ProxyConfigEntity,
    selected: Boolean,
    selectionLocked: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val canSelect = !selectionLocked
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (selectionLocked && !selected) 0.55f else 1f)
            .then(
                if (canSelect) {
                    Modifier.clickable(onClick = onSelect)
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = { if (canSelect) onSelect() },
                enabled = canSelect,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(config.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Listen ${config.listenHost}:${config.listenPort}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "→ ${config.connectHost}:${config.connectPort}  SNI ${config.fakeSni}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Method: ${config.method}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onEdit, enabled = canSelect) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete, enabled = canSelect) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditScreen(
    initial: ProxyConfigEntity?,
    onSave: (ProxyConfigEntity) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var listenHost by remember { mutableStateOf(initial?.listenHost ?: "0.0.0.0") }
    var listenPort by remember { mutableStateOf(initial?.listenPort?.toString() ?: "40443") }
    var connectHost by remember { mutableStateOf(initial?.connectHost ?: "104.19.229.21") }
    var connectPort by remember { mutableStateOf(initial?.connectPort?.toString() ?: "443") }
    var fakeSni by remember { mutableStateOf(initial?.fakeSni ?: "www.hcaptcha.com") }
    var method by remember { mutableStateOf(initial?.method ?: "combined") }
    var methodExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun trySave() {
        val validation = validateConfig(name, listenHost, listenPort, connectHost, connectPort, fakeSni, method)
        if (validation != null) {
            error = validation
        } else {
            onSave(
                ProxyConfigEntity(
                    id = initial?.id ?: 0,
                    name = name.trim(),
                    listenHost = listenHost.trim(),
                    listenPort = listenPort.toInt(),
                    connectHost = connectHost.trim(),
                    connectPort = connectPort.toInt(),
                    fakeSni = fakeSni.trim(),
                    method = method,
                    lastUsedAt = initial?.lastUsedAt ?: 0,
                ),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "New config" else "Edit config") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { trySave() }) {
                        Text("Save")
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = { trySave() },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("Save config")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(listenHost, { listenHost = it }, label = { Text("Local IP") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(listenPort, { listenPort = it }, label = { Text("Local port") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(connectHost, { connectHost = it }, label = { Text("SNI server IP") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(connectPort, { connectPort = it }, label = { Text("SNI server port") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fakeSni, { fakeSni = it }, label = { Text("SNI website") }, modifier = Modifier.fillMaxWidth())

            ExposedDropdownMenuBox(expanded = methodExpanded, onExpandedChange = { methodExpanded = it }) {
                OutlinedTextField(
                    value = method,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Method") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = methodExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = methodExpanded, onDismissRequest = { methodExpanded = false }) {
                    methods.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m) },
                            onClick = { method = m; methodExpanded = false },
                        )
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private fun validateConfig(
    name: String,
    listenHost: String,
    listenPort: String,
    connectHost: String,
    connectPort: String,
    fakeSni: String,
    method: String,
): String? {
    if (name.isBlank()) return "Name is required"
    if (listenHost.isBlank()) return "Local IP is required"
    if (connectHost.isBlank()) return "SNI server IP is required"
    if (fakeSni.isBlank()) return "SNI website is required"
    if (method !in methods) return "Invalid method"
    val lp = listenPort.toIntOrNull() ?: return "Invalid local port"
    val cp = connectPort.toIntOrNull() ?: return "Invalid SNI server port"
    if (lp !in 1..65535 || cp !in 1..65535) return "Port must be 1–65535"
    return null
}
