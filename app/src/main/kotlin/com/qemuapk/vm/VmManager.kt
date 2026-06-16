package com.qemuapk.vm

import android.content.Context
import android.util.Log
import com.qemuapk.qemu.KvmDetector
import com.qemuapk.qemu.ProotLauncher
import com.qemuapk.qemu.QemuProcess
import com.qemuapk.qemu.QemuRunConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Central manager for all virtual machine instances.
 * Handles VM creation, lifecycle management, and persistence.
 */
class VmManager(private val context: Context) {

    companion object {
        private const val TAG = "VmManager"
        private const val VMS_DIR = "vms"
        private const val IMAGES_DIR = "images"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prootLauncher = ProotLauncher(context)

    private val _vmInstances = MutableStateFlow<List<VmInstance>>(emptyList())
    val vmInstances: StateFlow<List<VmInstance>> = _vmInstances.asStateFlow()

    private val _activeVm = MutableStateFlow<VmInstance?>(null)
    val activeVm: StateFlow<VmInstance?> = _activeVm.asStateFlow()

    private val _isSetupComplete = MutableStateFlow(false)
    val isSetupComplete: StateFlow<Boolean> = _isSetupComplete.asStateFlow()

    private var qemuProcess: QemuProcess? = null
    private var uptimeJob: Job? = null

    private val vmsDir: File = File(context.filesDir, VMS_DIR)
    private val imagesDir: File = File(context.filesDir, IMAGES_DIR)

    init {
        vmsDir.mkdirs()
        imagesDir.mkdirs()
        loadSavedVms()
        _isSetupComplete.value = prootLauncher.isEnvironmentReady()
    }

    /**
     * Set up the proot environment (first-time or after update).
     */
    suspend fun setupEnvironment(onProgress: (Int, String) -> Unit) {
        try {
            prootLauncher.setupEnvironment(onProgress)
            _isSetupComplete.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Setup failed", e)
            throw e
        }
    }

    /**
     * Create a new VM instance with the given configuration.
     */
    fun createVm(config: VmConfig): VmInstance {
        val instance = VmInstance(config)
        val currentList = _vmInstances.value.toMutableList()
        currentList.add(instance)
        _vmInstances.value = currentList

        // Persist configuration
        saveVmConfig(config)

        Log.d(TAG, "Created VM: ${config.name} (${config.id})")
        return instance
    }

    /**
     * Start a VM instance.
     */
    suspend fun startVm(instance: VmInstance): Boolean {
        if (_activeVm.value != null) {
            Log.w(TAG, "Another VM is already active")
            return false
        }

        if (!_isSetupComplete.value) {
            instance.onError("Environment not set up. Please run initial setup first.")
            return false
        }

        instance.onStarting()
        _activeVm.value = instance

        try {
            // Build runtime config from VM config
            val runConfig = QemuRunConfig(
                ramMb = instance.config.ramMb,
                cpuCores = instance.config.cpuCores,
                displayWidth = instance.config.displayWidth,
                displayHeight = instance.config.displayHeight,
                kernelPath = resolveImagePath(instance.config.kernelPath),
                initrdPath = resolveImagePath(instance.config.initrdPath),
                systemImagePath = resolveImagePath(instance.config.systemImagePath),
                userdataImagePath = resolveImagePath(instance.config.userdataImagePath),
                cacheImagePath = resolveImagePath(instance.config.cacheImagePath),
                sharedFolderPath = resolveImagePath(instance.config.sharedFolderPath),
                bootArgs = instance.config.guestBootArgs,
                useKvm = instance.config.enableKvm && KvmDetector.isKvmAvailable(),
                adbHostPort = instance.config.adbHostPort
            )

            // Verify required images exist
            if (!File(runConfig.kernelPath).exists()) {
                instance.onError("Kernel image not found: ${runConfig.kernelPath}")
                _activeVm.value = null
                return false
            }

            // Start QEMU process
            qemuProcess = QemuProcess(prootLauncher)
            val started = qemuProcess?.start(runConfig) ?: false

            if (started) {
                instance.onRunning()
                startUptimeTimer(instance)
                startProcessMonitor(instance)
                Log.d(TAG, "VM started: ${instance.config.name}")
                return true
            } else {
                instance.onError("Failed to start QEMU process")
                _activeVm.value = null
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VM", e)
            instance.onError("Start failed: ${e.message}")
            _activeVm.value = null
            return false
        }
    }

    /**
     * Stop the active VM instance.
     */
    suspend fun stopVm(instance: VmInstance): Boolean {
        if (instance.currentState != VmState.RUNNING) {
            return true
        }

        instance.onStopping()
        uptimeJob?.cancel()

        try {
            val stopped = qemuProcess?.stop() ?: true
            instance.onStopped()
            if (_activeVm.value == instance) {
                _activeVm.value = null
            }
            Log.d(TAG, "VM stopped: ${instance.config.name}")
            return stopped
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VM", e)
            instance.onError("Stop failed: ${e.message}")
            return false
        } finally {
            qemuProcess = null
        }
    }

    /**
     * Delete a VM instance and its disk images.
     */
    fun deleteVm(instance: VmInstance) {
        scope.launch {
            if (instance.isRunning) {
                stopVm(instance)
            }

            val currentList = _vmInstances.value.toMutableList()
            currentList.remove(instance)
            _vmInstances.value = currentList

            // Delete config file
            val configFile = File(vmsDir, "${instance.config.id}.json")
            configFile.delete()

            // Delete disk images
            File(instance.config.userdataImagePath).delete()
            File(instance.config.cacheImagePath).delete()

            Log.d(TAG, "Deleted VM: ${instance.config.name}")
        }
    }

    /**
     * Get the QEMU process for direct interaction (e.g., VNC connection).
     */
    fun getQemuProcess(): QemuProcess? = qemuProcess

    /**
     * Get the proot launcher for executing commands in the proot environment.
     */
    fun getProotLauncher(): ProotLauncher = prootLauncher

    /**
     * Get the images directory path.
     */
    fun getImagesDir(): File = imagesDir

    private fun loadSavedVms() {
        val instances = mutableListOf<VmInstance>()
        vmsDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val config = VmConfig.fromFile(file)
                instances.add(VmInstance(config))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load VM config from ${file.name}", e)
            }
        }
        _vmInstances.value = instances
        Log.d(TAG, "Loaded ${instances.size} saved VMs")
    }

    private fun saveVmConfig(config: VmConfig) {
        val configFile = File(vmsDir, "${config.id}.json")
        config.saveToFile(configFile)
    }

    private fun resolveImagePath(path: String): String {
        if (path.isEmpty()) return path
        // If path is already absolute and exists, return as-is
        val file = File(path)
        if (file.isAbsolute && file.exists()) return path
        // Otherwise, look in images directory
        val inImagesDir = File(imagesDir, File(path).name)
        return if (inImagesDir.exists()) inImagesDir.absolutePath else path
    }

    private fun startUptimeTimer(instance: VmInstance) {
        uptimeJob?.cancel()
        uptimeJob = scope.launch {
            while (instance.isRunning) {
                instance.updateUptime()
                delay(1000)
            }
        }
    }

    private fun startProcessMonitor(instance: VmInstance) {
        scope.launch(Dispatchers.IO) {
            while (instance.isRunning) {
                delay(2000)
                if (qemuProcess != null && !qemuProcess!!.checkAlive()) {
                    Log.w(TAG, "QEMU process died unexpectedly")
                    instance.onError("QEMU process exited unexpectedly")
                    _activeVm.value = null
                    uptimeJob?.cancel()
                    break
                }
            }
        }
    }
}
