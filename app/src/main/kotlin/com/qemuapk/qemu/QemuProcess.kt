package com.qemuapk.qemu

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Manages the QEMU process lifecycle.
 * Handles launching, monitoring, and stopping the qemu-system-arm process
 * within the proot environment.
 */
class QemuProcess(
    private val prootLauncher: ProotLauncher
) {
    companion object {
        private const val TAG = "QemuProcess"

        /** Timeout to wait for QEMU process to start (ms) */
        const val START_TIMEOUT_MS = 10_000L

        /** Timeout to wait for QEMU process to stop gracefully (ms) */
        const val STOP_TIMEOUT_MS = 15_000L
    }

    private var process: Process? = null

    private val _isAlive = MutableStateFlow(false)
    val isAlive: StateFlow<Boolean> = _isAlive.asStateFlow()

    private val _outputLines = MutableSharedFlow<String>(replay = 100)
    val outputLines: SharedFlow<String> = _outputLines.asSharedFlow()

    private val _errorLines = MutableSharedFlow<String>(replay = 100)
    val errorLines: SharedFlow<String> = _errorLines.asSharedFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private var outputThread: Thread? = null
    private var errorThread: Thread? = null

    /**
     * Start the QEMU process with the given configuration.
     *
     * @param config QEMU configuration to use
     * @return true if the process started successfully
     */
    suspend fun start(config: QemuRunConfig): Boolean = withContext(Dispatchers.IO) {
        if (_isAlive.value) {
            Log.w(TAG, "QEMU process already running")
            return@withContext false
        }

        try {
            // Build the QEMU command
            val qemuArgs = buildQemuArgs(config)
            val imageDir = prootLauncher.getImageHostDir()
            val command = prootLauncher.buildQemuCommand(qemuArgs, imageDir)

            Log.d(TAG, "Starting QEMU with command: ${command.joinToString(" ")}")

            // Launch the process
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(false)
                .directory(prootLauncher.getImageHostDir())

            process = processBuilder.start()
            _isAlive.value = true
            _exitCode.value = null

            // Start output capture threads
            startOutputCapture()

            Log.d(TAG, "QEMU process started with PID: ${getProcessId()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QEMU process", e)
            _errorLines.emit("Failed to start: ${e.message}")
            _isAlive.value = false
            false
        }
    }

    /**
     * Stop the QEMU process gracefully.
     * Sends a system_powerdown command via the QEMU monitor,
     * then falls back to SIGTERM/SIGKILL if it doesn't exit in time.
     */
    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val proc = process ?: return@withContext true

        if (!_isAlive.value) return@withContext true

        try {
            Log.d(TAG, "Stopping QEMU process...")

            // Try graceful shutdown via QEMU monitor (serial)
            try {
                sendMonitorCommand("system_powerdown")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send monitor command: ${e.message}")
            }

            // Wait for graceful exit
            val exited = waitForExit(STOP_TIMEOUT_MS)

            if (!exited) {
                Log.w(TAG, "QEMU did not exit gracefully, forcing...")
                proc.destroyForcibly()
                proc.waitFor()
            }

            _exitCode.value = proc.exitValue()
            _isAlive.value = false

            Log.d(TAG, "QEMU process exited with code: ${_exitCode.value}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping QEMU process", e)
            false
        } finally {
            cleanup()
        }
    }

    /**
     * Force kill the QEMU process immediately.
     */
    fun forceKill() {
        try {
            process?.destroyForcibly()
            _isAlive.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Error force killing QEMU", e)
        }
    }

    /**
     * Send a command to the QEMU monitor via the serial port.
     */
    fun sendMonitorCommand(command: String) {
        try {
            val writer = OutputStreamWriter(process?.outputStream)
            writer.write("$command\n")
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send monitor command", e)
        }
    }

    /**
     * Check if the process is still running.
     */
    fun checkAlive(): Boolean {
        val proc = process ?: return false
        return try {
            proc.exitValue()
            false // exitValue() succeeded, meaning process has exited
        } catch (e: IllegalThreadStateException) {
            true // Process is still running
        }
    }

    private fun buildQemuArgs(config: QemuRunConfig): List<String> {
        val builder = QemuConfigBuilder()
            .machine("virt")
            .cpu("cortex-a15")
            .smp(config.cpuCores)
            .memory(config.ramMb)

        // Acceleration
        if (config.useKvm) {
            builder.accel("kvm")
        }

        // Kernel and boot — translate paths to proot-visible paths
        builder.kernel(translateToProotPath(config.kernelPath))
        if (config.initrdPath.isNotEmpty()) {
            builder.initrd(translateToProotPath(config.initrdPath))
        }
        builder.append(config.bootArgs)

        // Disk images
        if (config.systemImagePath.isNotEmpty()) {
            builder.drive(translateToProotPath(config.systemImagePath), format = "raw", ifType = "virtio", readonly = true)
        }
        if (config.userdataImagePath.isNotEmpty()) {
            builder.drive(translateToProotPath(config.userdataImagePath), format = "qcow2", ifType = "virtio")
        }
        if (config.cacheImagePath.isNotEmpty()) {
            builder.drive(translateToProotPath(config.cacheImagePath), format = "raw", ifType = "virtio")
        }

        // Display
        builder.device("virtio-gpu-pci",
            "xres" to config.displayWidth,
            "yres" to config.displayHeight
        )
        builder.displayVnc("127.0.0.1:0")

        // Input devices
        builder.device("virtio-tablet-pci")
        builder.device("virtio-keyboard-pci")

        // Network with ADB port forwarding
        builder.networkUser(
            id = "net0",
            hostForward = "tcp::${config.adbHostPort}-:5555"
        )

        // Shared folder
        if (config.sharedFolderPath.isNotEmpty()) {
            builder.sharedFolder(translateToProotPath(config.sharedFolderPath))
        }

        // Misc
        builder.noReboot()
        builder.serial("mon:stdio")

        return builder.build()
    }

    /**
     * Translate a host filesystem path to the equivalent path inside the proot environment.
     *
     * Inside proot:
     * - filesDir is bind-mounted at /mnt/app
     * - imageDir (filesDir/images) is bind-mounted at /mnt/images
     *
     * Host path: /data/data/com.qemuapk/files/images/zImage
     * Proot path: /mnt/images/zImage
     */
    private fun translateToProotPath(hostPath: String): String {
        if (hostPath.isEmpty()) return hostPath

        val file = java.io.File(hostPath)
        val fileName = file.name

        // Images directory is bind-mounted at /mnt/images
        val imagesDirSuffix = "images"
        val imagesIndex = hostPath.lastIndexOf("/$imagesDirSuffix/")
        if (imagesIndex >= 0) {
            // Everything after the images/ directory
            val relativePath = hostPath.substring(imagesIndex + imagesDirSuffix.length + 1)
            return "/mnt/images/$relativePath"
        }

        // App files directory is bind-mounted at /mnt/app
        val filesDirSuffix = "files/"
        val filesIndex = hostPath.lastIndexOf(filesDirSuffix)
        if (filesIndex >= 0) {
            val relativePath = hostPath.substring(filesIndex + filesDirSuffix.length)
            return "/mnt/app/$relativePath"
        }

        // Fallback: just use the filename in /mnt/images/
        Log.w(TAG, "Could not translate path, using /mnt/images/$fileName")
        return "/mnt/images/$fileName"
    }

    private fun startOutputCapture() {
        outputThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                reader.forEachLine { line ->
                    Log.d("QEMU_OUT", line)
                    _outputLines.tryEmit(line)
                }
            } catch (e: Exception) {
                if (_isAlive.value) {
                    Log.e(TAG, "Output capture error", e)
                }
            }
        }.apply {
            name = "qemu-stdout"
            isDaemon = true
            start()
        }

        errorThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(process?.errorStream))
                reader.forEachLine { line ->
                    Log.w("QEMU_ERR", line)
                    _errorLines.tryEmit(line)
                }
            } catch (e: Exception) {
                if (_isAlive.value) {
                    Log.e(TAG, "Error capture error", e)
                }
            }
        }.apply {
            name = "qemu-stderr"
            isDaemon = true
            start()
        }
    }

    private fun waitForExit(timeoutMs: Long): Boolean {
        return try {
            process?.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: true
        } catch (e: InterruptedException) {
            false
        }
    }

    private fun cleanup() {
        outputThread?.interrupt()
        errorThread?.interrupt()
        outputThread = null
        errorThread = null
        process = null
    }

    private fun getProcessId(): Long {
        return try {
            val p = process ?: return -1L
            // Try Java 9+ Process.pid() via reflection (available on Android API 26+)
            val pidMethod = p.javaClass.getMethod("pid")
            pidMethod.invoke(p) as Long
        } catch (e: Exception) {
            -1L
        }
    }
}

/**
 * Runtime configuration for a QEMU VM launch.
 * Separated from VmConfig to allow runtime overrides.
 */
data class QemuRunConfig(
    val ramMb: Int = 1024,
    val cpuCores: Int = 2,
    val displayWidth: Int = 720,
    val displayHeight: Int = 1280,
    val kernelPath: String,
    val initrdPath: String = "",
    val systemImagePath: String,
    val userdataImagePath: String,
    val cacheImagePath: String = "",
    val sharedFolderPath: String = "",
    val bootArgs: String = "console=ttyAMA0 androidboot.hardware=cuttlefish androidboot.selinux=permissive",
    val useKvm: Boolean = false,
    val adbHostPort: Int = 5555
)
