package com.qemuapk.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qemuapk.qemu.KvmDetector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSetupEnvironment: () -> Unit,
    isSetupComplete: Boolean
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Environment setup section
            SectionHeader("Environment")

            EnvironmentStatus(
                isSetupComplete = isSetupComplete,
                onSetup = onSetupEnvironment
            )

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(24.dp))

            // KVM status
            SectionHeader("Hardware Acceleration")
            KvmStatus()

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(24.dp))

            // Default VM settings
            SectionHeader("Default VM Settings")
            DefaultVmSettings()

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(24.dp))

            // About
            SectionHeader("About")
            AboutSection()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun EnvironmentStatus(isSetupComplete: Boolean, onSetup: () -> Unit) {
    Column {
        Text(
            text = if (isSetupComplete) "Environment: Ready" else "Environment: Not Set Up",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isSetupComplete) {
                "Proot and Alpine Linux are configured and ready."
            } else {
                "The proot environment and guest system image need to be set up before you can create VMs."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!isSetupComplete) {
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.FilledTonalButton(onClick = onSetup) {
                Text("Run Setup")
            }
        }
    }
}

@Composable
private fun KvmStatus() {
    val kvmAvailable = remember { KvmDetector.isKvmAvailable() }

    Column {
        Text(
            text = if (kvmAvailable) "KVM: Available" else "KVM: Not Available",
            style = MaterialTheme.typography.bodyLarge,
            color = if (kvmAvailable) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = KvmDetector.getStatusDescription(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DefaultVmSettings() {
    var ramMb by remember { mutableStateOf(1024f) }
    var cpuCores by remember { mutableStateOf(2f) }

    Column {
        Text(text = "RAM: ${ramMb.toInt()} MB")
        Slider(
            value = ramMb,
            onValueChange = { ramMb = it },
            valueRange = 512f..4096f,
            steps = 7,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(text = "CPU Cores: ${cpuCores.toInt()}")
        Slider(
            value = cpuCores,
            onValueChange = { cpuCores = it },
            valueRange = 1f..8f,
            steps = 6,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AboutSection() {
    Column {
        Text(
            text = "QEMU APK v0.1.0",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Run ARM32 Android apps on ARM64 devices using QEMU virtualization.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Based on QEMU system emulation with proot + Alpine Linux.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
