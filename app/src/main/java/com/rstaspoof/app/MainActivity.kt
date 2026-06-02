package com.rstaspoof.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rstaspoof.app.service.ProxyRunState
import com.rstaspoof.app.ui.ConfigEditScreen
import com.rstaspoof.app.ui.ConfigListScreen
import com.rstaspoof.app.ui.LogScreen
import com.rstaspoof.app.vm.ProxyViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: ProxyViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        viewModel.bindIfRunning()

        setContent {
            @OptIn(ExperimentalMaterial3Api::class)
            MaterialTheme(colorScheme = darkColorScheme()) {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()
                var tab by rememberSaveable { mutableIntStateOf(0) }

                val configs by viewModel.configs.collectAsState()
                val selectedId by viewModel.selectedId.collectAsState()

                androidx.compose.runtime.LaunchedEffect(configs, selectedId) {
                    if (selectedId == null && configs.isNotEmpty()) {
                        viewModel.selectConfig(configs.first().id)
                    }
                }
                val runState by viewModel.runState.collectAsState()
                val logs by viewModel.logs.collectAsState()

                val runLabel = when (runState) {
                    is ProxyRunState.Idle -> "Idle"
                    is ProxyRunState.Starting -> "Starting…"
                    is ProxyRunState.Running -> "Running"
                    is ProxyRunState.Error -> (runState as ProxyRunState.Error).message
                }
                val isRunning =
                    runState is ProxyRunState.Running || runState is ProxyRunState.Starting

                val onConfigsTab = tab == 0
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val showRunFab = (onConfigsTab && currentRoute == "list") || tab == 1
                val canStart = selectedId != null && configs.isNotEmpty()
                val fabEnabled = isRunning || canStart

                Scaffold(
                    topBar = {
                        when {
                            onConfigsTab && currentRoute == "list" -> {
                                TopAppBar(
                                    title = {
                                        Column {
                                            Text("Proxy configs")
                                            Text(
                                                runLabel,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    },
                                    actions = {
                                        IconButton(onClick = { navController.navigate("edit/0") }) {
                                            Icon(
                                                Icons.Default.Add,
                                                contentDescription = "Add config"
                                            )
                                        }
                                    },
                                )
                            }

                            !onConfigsTab -> {
                                TopAppBar(
                                    title = {
                                        Text(
                                            "Terminal",
                                            style = TextStyle(fontFamily = FontFamily.Monospace),
                                        )
                                    },
                                )
                            }
                        }
                    },
                    floatingActionButton = {
                        if (showRunFab) {
                            FloatingActionButton(
                                onClick = { if (fabEnabled) viewModel.toggleProxy() },
                                modifier = Modifier.alpha(if (fabEnabled) 1f else 0.38f),
                                containerColor = if (isRunning) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                contentColor = if (isRunning) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                elevation = FloatingActionButtonDefaults.elevation(),
                            ) {
                                Icon(
                                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isRunning) "Stop proxy" else "Start proxy",
                                )
                            }
                        }
                    },
                    bottomBar = {
                        if (currentRoute?.startsWith("edit") != true) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = tab == 0,
                                    onClick = { tab = 0 },
                                    icon = { Text("⚙") },
                                    label = { Text("Configs") },
                                )
                                NavigationBarItem(
                                    selected = tab == 1,
                                    onClick = { tab = 1 },
                                    icon = { Text("📋") },
                                    label = { Text("Logs") },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val onEditScreen = currentRoute?.startsWith("edit") == true
                    val navModifier = if (onEditScreen) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.padding(padding)
                    }
                    when (tab) {
                        0 -> NavHost(
                            navController = navController,
                            startDestination = "list",
                            modifier = navModifier,
                        ) {
                            composable("list") {
                                ConfigListScreen(
                                    configs = configs,
                                    selectedId = selectedId,
                                    selectionLocked = isRunning,
                                    contentPadding = PaddingValues(0.dp),
                                    onSelect = { viewModel.selectConfig(it) },
                                    onEdit = { navController.navigate("edit/$it") },
                                    onDelete = { config ->
                                        scope.launch { viewModel.deleteConfig(config) }
                                    },
                                )
                            }
                            composable(
                                route = "edit/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.LongType }),
                            ) { entry ->
                                val id = entry.arguments?.getLong("id") ?: 0L
                                ConfigEditRoute(
                                    configId = id,
                                    viewModel = viewModel,
                                    onSaved = { navController.popBackStack() },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }

                        1 -> LogScreen(
                            logs = logs,
                            contentPadding = padding,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.bindIfRunning()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ConfigEditRoute(
    configId: Long,
    viewModel: ProxyViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var initial by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.rstaspoof.app.data.ProxyConfigEntity?>(
            null
        )
    }
    var loaded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(configId) {
        initial = if (configId == 0L) null else viewModel.getConfig(configId)
        loaded = true
    }

    if (!loaded) {
        Text("Loading…")
    } else {
        ConfigEditScreen(
            initial = initial,
            onSave = { entity ->
                scope.launch {
                    val id = viewModel.saveConfig(entity)
                    viewModel.selectConfig(id)
                    onSaved()
                }
            },
            onBack = onBack,
        )
    }
}
