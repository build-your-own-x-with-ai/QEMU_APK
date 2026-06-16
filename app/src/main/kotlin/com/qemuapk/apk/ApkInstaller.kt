package com.qemuapk.apk

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Socket

/**
 * Handles installing APK files into the guest Android system via ADB.
 * The guest runs adbd on port 5555, which is forwarded to the host
 * via QEMU's SLIRP networking.
 */
class ApkInstaller(
    private val adbHost: String = "127.0.0.1",
    private val adbPort: Int = 5555
) {
    companion object {
        private const val TAG = "ApkInstaller"

        /** Maximum time to wait for ADB connection (ms) */
        const val ADB_CONNECT_TIMEOUT_MS = 30_000L

        /** ADB protocol: system type for install */
        private const val ADB_INSTALL_CMD = "exec:pm install -r -t"
    }

    /**
     * Check if ADB is accessible on the guest VM.
     * @return true if ADB connection can be established
     */
    suspend fun isAdbAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(adbHost, adbPort), 3000)
            val available = socket.isConnected
            socket.close()
            available
        } catch (e: Exception) {
            Log.d(TAG, "ADB not available: ${e.message}")
            false
        }
    }

    /**
     * Wait for ADB to become available (e.g., after VM boot).
     * @param timeoutMs Maximum time to wait
     * @return true if ADB became available within the timeout
     */
    suspend fun waitForAdb(timeoutMs: Long = ADB_CONNECT_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (isAdbAvailable()) {
                    Log.d(TAG, "ADB is available")
                    return@withContext true
                }
                delay(2000) // Check every 2 seconds
                Log.d(TAG, "Waiting for ADB...")
            }
            Log.w(TAG, "ADB timeout after ${timeoutMs}ms")
            false
        }

    /**
     * Install an APK file into the guest Android system.
     *
     * This uses a simplified approach:
     * 1. Copy the APK to the shared folder accessible by the guest
     * 2. Execute pm install in the guest via ADB
     *
     * @param apkFile The APK file to install (must be accessible from within the guest)
     * @param guestPath Path to the APK inside the guest filesystem
     * @return Installation result
     */
    suspend fun installApk(apkFile: java.io.File, guestPath: String): InstallResult =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Installing APK: ${apkFile.name} from guest path: $guestPath")

                // Use ADB protocol to install
                val result = executeAdbCommand("shell:pm install -r -t \"$guestPath\"")

                if (result.contains("Success")) {
                    Log.d(TAG, "APK installed successfully: ${apkFile.name}")
                    InstallResult(
                        success = true,
                        packageName = apkFile.nameWithoutExtension,
                        message = "Installed successfully"
                    )
                } else {
                    val errorMsg = result.trim()
                    Log.e(TAG, "APK install failed: $errorMsg")
                    InstallResult(
                        success = false,
                        packageName = apkFile.nameWithoutExtension,
                        message = "Install failed: $errorMsg"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install APK", e)
                InstallResult(
                    success = false,
                    packageName = apkFile.nameWithoutExtension,
                    message = "Error: ${e.message}"
                )
            }
        }

    /**
     * Install APK by pushing it to the guest first via shared folder,
     * then installing from the shared mount point.
     *
     * @param apkFile APK file on the host
     * @param sharedFolderHostPath Host path of the shared folder
     * @return Installation result
     */
    suspend fun installViaSharedFolder(
        apkFile: java.io.File,
        sharedFolderHostPath: String
    ): InstallResult = withContext(Dispatchers.IO) {
        try {
            // Copy APK to shared folder
            val sharedDir = java.io.File(sharedFolderHostPath)
            sharedDir.mkdirs()
            val targetFile = java.io.File(sharedDir, apkFile.name)
            apkFile.copyTo(targetFile, overwrite = true)

            // Guest mount path for shared folder
            val guestPath = "/mnt/shared/${apkFile.name}"

            Log.d(TAG, "APK copied to shared folder, installing from $guestPath")
            installApk(apkFile, guestPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install via shared folder", e)
            InstallResult(
                success = false,
                packageName = apkFile.nameWithoutExtension,
                message = "Shared folder install failed: ${e.message}"
            )
        }
    }

    /**
     * List installed packages in the guest system.
     * @return List of package names
     */
    suspend fun listInstalledPackages(): List<String> = withContext(Dispatchers.IO) {
        try {
            val result = executeAdbCommand("shell:pm list packages -3")
            result.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list packages", e)
            emptyList()
        }
    }

    /**
     * Uninstall a package from the guest system.
     * @param packageName Package name to uninstall
     * @return true if uninstall succeeded
     */
    suspend fun uninstallPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = executeAdbCommand("shell:pm uninstall $packageName")
            result.contains("Success")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to uninstall $packageName", e)
            false
        }
    }

    /**
     * Launch an app in the guest system.
     * @param packageName Package name to launch
     */
    suspend fun launchApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = executeAdbCommand(
                "shell:monkey -p $packageName -c android.intent.category.LAUNCHER 1"
            )
            !result.contains("Error") && !result.contains("No activities found")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName", e)
            false
        }
    }

    /**
     * Execute an ADB command using a raw ADB protocol connection.
     * This is a simplified ADB client that speaks the ADB wire protocol.
     */
    private fun executeAdbCommand(command: String): String {
        var socket: Socket? = null
        try {
            socket = Socket(adbHost, adbPort)
            socket.soTimeout = 30_000

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // ADB protocol: connect to "host:transport-any"
            sendAdbMessage(output, "host:transport-any")
            readAdbResponse(input) // OKAY

            // Send the actual command
            sendAdbMessage(output, command)
            readAdbResponse(input) // OKAY

            // Read the response data
            val response = StringBuilder()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val len = readAdbDataLength(input)
                    if (len <= 0) break
                    val data = ByteArray(len)
                    var totalRead = 0
                    while (totalRead < len) {
                        val read = input.read(data, totalRead, len - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    response.append(String(data, 0, totalRead))
                }
            } catch (e: Exception) {
                // End of data
            }

            return response.toString()
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun sendAdbMessage(output: java.io.OutputStream, message: String) {
        val hexLen = "%04x".format(message.length)
        output.write("$hexLen$message".toByteArray())
        output.flush()
    }

    private fun readAdbResponse(input: java.io.InputStream): Boolean {
        val status = ByteArray(4)
        readFully(input, status)
        val statusStr = String(status)

        if (statusStr == "OKAY") {
            return true
        } else if (statusStr == "FAIL") {
            val lenHex = ByteArray(4)
            readFully(input, lenHex)
            val len = Integer.parseInt(String(lenHex), 16)
            val msg = ByteArray(len)
            readFully(input, msg)
            throw IOException("ADB error: ${String(msg)}")
        }

        throw IOException("Unexpected ADB response: $statusStr")
    }

    private fun readAdbDataLength(input: java.io.InputStream): Int {
        val hexLen = ByteArray(4)
        try {
            readFully(input, hexLen)
            return Integer.parseInt(String(hexLen), 16)
        } catch (e: Exception) {
            return -1
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw IOException("Unexpected end of stream")
            offset += read
        }
    }
}

/**
 * Result of an APK installation attempt.
 */
data class InstallResult(
    val success: Boolean,
    val packageName: String,
    val message: String
)
