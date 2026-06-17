package com.qemuapk

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.qemuapk.ui.screens.SettingsScreen
import com.qemuapk.ui.screens.VmListScreen
import com.qemuapk.ui.screens.VmRunningScreen
import com.qemuapk.ui.theme.QemuApkTheme
import com.qemuapk.vm.VmForegroundService
import com.qemuapk.vm.VmInstance
import com.qemuapk.vm.VmManager
import com.qemuapk.vm.VmState
import kotlinx.coroutines.launch

/**
 * Main entry point for the QEMU APK application.
 * Manages navigation between VM list, running VM, and settings screens.
 */
class MainActivity : ComponentActivity() {

    private lateinit var vmManager: VmManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vmManager = VmManager(applicationContext)

        setContent {
            QemuApkTheme {
                val vmInstances by vmManager.vmInstances.collectAsState()
                val activeVm by vmManager.activeVm.collectAsState()
                val isSetupComplete by vmManager.isSetupComplete.collectAsState()
                val imagesReady by vmManager.imagesReady.collectAsState()

                val scope = rememberCoroutineScope()
                var currentScreen by remember { mutableStateOf<Screen>(Screen.VmList) }

                // If a VM is active and running, show the running screen
                val runningVm = activeVm?.takeIf { it.currentState == VmState.RUNNING }

                when {
                    runningVm != null && currentScreen != Screen.Settings -> {
                        VmRunningScreen(
                            vmInstance = runningVm,
                            onBack = {
                                scope.launch { vmManager.stopVm(runningVm) }
                                VmForegroundService.stop(this@MainActivity)
                            },
                            onStop = {
                                scope.launch { vmManager.stopVm(runningVm) }
                                VmForegroundService.stop(this@MainActivity)
                            }
                        )
                    }

                    currentScreen == Screen.Settings -> {
                        SettingsScreen(
                            onBack = { currentScreen = Screen.VmList },
                            onSetupEnvironment = {
                                lifecycleScope.launch {
                                    try {
                                        vmManager.setupEnvironment { progress, message ->
                                            // Progress callback
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Setup failed", e)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Setup failed: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            onDownloadImages = {
                                lifecycleScope.launch {
                                    try {
                                        vmManager.downloadImages()
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Images downloaded successfully",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Download failed", e)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Download failed: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            isSetupComplete = isSetupComplete,
                            imagesReady = imagesReady,
                            imageStatuses = emptyMap()
                        )
                    }

                    else -> {
                        VmListScreen(
                            vmInstances = vmInstances,
                            imagesReady = imagesReady,
                            onDownloadImages = { onProgress ->
                                lifecycleScope.launch {
                                    try {
                                        vmManager.downloadImages(onProgress = onProgress)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Download failed", e)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Download failed: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            onCreateVm = { config ->
                                val instance = vmManager.createVm(config)
                                scope.launch {
                                    val started = vmManager.startVm(instance)
                                    if (started) {
                                        VmForegroundService.start(
                                            this@MainActivity,
                                            instance.config.name
                                        )
                                    }
                                }
                            },
                            onStartVm = { instance ->
                                scope.launch {
                                    val started = vmManager.startVm(instance)
                                    if (started) {
                                        VmForegroundService.start(
                                            this@MainActivity,
                                            instance.config.name
                                        )
                                    }
                                }
                            },
                            onStopVm = { instance ->
                                scope.launch {
                                    vmManager.stopVm(instance)
                                    VmForegroundService.stop(this@MainActivity)
                                }
                            },
                            onDeleteVm = { instance ->
                                vmManager.deleteVm(instance)
                            },
                            onSettingsClick = {
                                currentScreen = Screen.Settings
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop any running VM when activity is destroyed (not just backgrounded)
        if (isFinishing) {
            lifecycleScope.launch {
                vmManager.activeVm.value?.let { vmManager.stopVm(it) }
                VmForegroundService.stop(this@MainActivity)
            }
        }
    }

    private sealed class Screen {
        object VmList : Screen()
        object Settings : Screen()
    }
}
