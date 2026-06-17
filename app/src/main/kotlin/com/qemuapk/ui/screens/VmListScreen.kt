package com.qemuapk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qemuapk.vm.VmConfig
import com.qemuapk.vm.VmInstance
import com.qemuapk.vm.VmState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmListScreen(
    vmInstances: List<VmInstance>,
    imagesReady: Boolean,
    onDownloadImages: (onProgress: (Int, String, Long, Long) -> Unit) -> Unit,
    onCreateVm: (VmConfig) -> Unit,
    onStartVm: (VmInstance) -> Unit,
    onStopVm: (VmInstance) -> Unit,
    onDeleteVm: (VmInstance) -> Unit,
    onSettingsClick: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var vmToDelete by remember { mutableStateOf<VmInstance?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadFileName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QEMU APK") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = "Create") },
                text = { Text("New VM") }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Image download card (shown when images not ready)
            if (!imagesReady) {
                item {
                    ImageDownloadCard(
                        isDownloading = isDownloading,
                        progress = downloadProgress,
                        currentFile = downloadFileName,
                        onDownload = {
                            isDownloading = true
                            onDownloadImages { percent, fileName, _, _ ->
                                downloadProgress = percent
                                downloadFileName = fileName
                                if (percent >= 100) {
                                    isDownloading = false
                                }
                            }
                        }
                    )
                }
            }

            // VM list
            if (vmInstances.isEmpty()) {
                item { EmptyState() }
            } else {
                items(vmInstances, key = { it.config.id }) { instance ->
                    VmCard(
                        instance = instance,
                        onStart = { onStartVm(instance) },
                        onStop = { onStopVm(instance) },
                        onDelete = { vmToDelete = instance }
                    )
                }
            }
        }
    }

    // Create VM dialog
    if (showCreateDialog) {
        CreateVmDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { config ->
                onCreateVm(config)
                showCreateDialog = false
            }
        )
    }

    // Delete confirmation dialog
    vmToDelete?.let { instance ->
        AlertDialog(
            onDismissRequest = { vmToDelete = null },
            title = { Text("Delete Virtual Machine?") },
            text = { Text("This will permanently delete \"${instance.config.name}\" and all its data.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteVm(instance)
                    vmToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vmToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No Virtual Machines",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Create a VM to run ARM32 apps",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImageDownloadCard(
    isDownloading: Boolean,
    progress: Int,
    currentFile: String,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "System Images Required",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Download the ARM32 kernel and rootfs before creating a VM (~16 MB).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isDownloading) {
                // Progress display
                LinearProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = currentFile,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "$progress%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                androidx.compose.material3.FilledTonalButton(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download System Images")
                }
            }
        }
    }
}

@Composable
private fun VmCard(
    instance: VmInstance,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    val state by instance.state.collectAsState()
    val uptime by instance.uptimeMs.collectAsState()
    val error by instance.errorMessage.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = instance.config.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${instance.config.ramMb} MB RAM · ${instance.config.cpuCores} cores",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // State badge
                VmStateBadge(state, uptime)
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                when (state) {
                    VmState.STOPPED, VmState.ERROR -> {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onStart) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                        }
                    }
                    VmState.RUNNING -> {
                        IconButton(onClick = onStop) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    }
                    VmState.STARTING, VmState.STOPPING -> {
                        // No actions during transitions
                    }
                }
            }
        }
    }
}

@Composable
private fun VmStateBadge(state: VmState, uptimeMs: Long) {
    val (text, color) = when (state) {
        VmState.STOPPED -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
        VmState.STARTING -> "Starting…" to MaterialTheme.colorScheme.primary
        VmState.RUNNING -> "Running (${formatUptime(uptimeMs)})" to MaterialTheme.colorScheme.primary
        VmState.STOPPING -> "Stopping…" to MaterialTheme.colorScheme.error
        VmState.ERROR -> "Error" to MaterialTheme.colorScheme.error
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

@Composable
private fun CreateVmDialog(
    onDismiss: () -> Unit,
    onCreate: (VmConfig) -> Unit
) {
    var name by remember { mutableStateOf("Android ARM32") }
    var ramMb by remember { mutableStateOf(1024f) }
    var cpuCores by remember { mutableStateOf(2f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Virtual Machine") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("VM Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("RAM: ${ramMb.toInt()} MB")
                Slider(
                    value = ramMb,
                    onValueChange = { ramMb = it },
                    valueRange = 512f..2048f,
                    steps = 3
                )

                Text("CPU Cores: ${cpuCores.toInt()}")
                Slider(
                    value = cpuCores,
                    onValueChange = { cpuCores = it },
                    valueRange = 1f..4f,
                    steps = 2
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(
                    VmConfig(
                        name = name,
                        ramMb = ramMb.toInt(),
                        cpuCores = cpuCores.toInt()
                    )
                )
            }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatUptime(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "%d:%02d:%02d".format(hours, minutes % 60, seconds % 60)
        else -> "%d:%02d".format(minutes, seconds % 60)
    }
}
