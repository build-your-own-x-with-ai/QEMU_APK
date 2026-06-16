package com.qemuapk.qemu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Manages the proot environment and Alpine Linux rootfs.
 * Proot provides a glibc environment on Android (which uses bionic libc),
 * allowing standard Linux binaries like QEMU to run.
 *
 * Architecture follows Vectras VM approach:
 * - Extract proot binary (aarch64 static) from assets
 * - Extract minimal Alpine Linux ARM64 rootfs from assets
 * - Install qemu-system-arm via Alpine's apk package manager (or use pre-built binary)
 * - Launch QEMU commands within the proot session
 */
class ProotLauncher(private val context: Context) {

    companion object {
        private const val TAG = "ProotLauncher"
        private const val PROOT_ASSET = "proot/proot"
        private const val ALPINE_ROOTFS_ASSET = "alpine/alpine-minirootfs.tar.gz"
        private const val PROOT_DIR = "proot_env"
        private const val ROOTFS_DIR = "rootfs"
        private const val PROOT_BINARY = "proot"
    }

    private val filesDir: File = context.filesDir
    private val prootDir: File = File(filesDir, PROOT_DIR)
    private val rootfsDir: File = File(prootDir, ROOTFS_DIR)
    private val prootBinary: File = File(prootDir, PROOT_BINARY)

    /**
     * Check if the proot environment has been set up.
     */
    fun isEnvironmentReady(): Boolean {
        return prootBinary.exists() && prootBinary.canExecute() && rootfsDir.exists()
    }

    /**
     * Set up the proot environment by extracting assets.
     * This should be called on first launch or after an update.
     *
     * @param onProgress Callback for progress updates (0-100)
     */
    suspend fun setupEnvironment(onProgress: (Int, String) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            try {
                onProgress(0, "Preparing directories...")
                prootDir.mkdirs()
                rootfsDir.mkdirs()

                // Step 1: Extract proot binary
                onProgress(10, "Extracting proot binary...")
                extractAsset(PROOT_ASSET, prootBinary)
                prootBinary.setExecutable(true)
                Log.d(TAG, "Proot binary extracted to ${prootBinary.absolutePath}")

                // Step 2: Extract Alpine rootfs
                onProgress(30, "Extracting Alpine Linux rootfs...")
                val rootfsTarball = File(prootDir, "alpine-rootfs.tar.gz")
                extractAsset(ALPINE_ROOTFS_ASSET, rootfsTarball)

                // Step 3: Extract rootfs tarball
                onProgress(50, "Unpacking rootfs...")
                extractTarball(rootfsTarball, rootfsDir)
                rootfsTarball.delete()

                // Step 4: Set up necessary directories inside rootfs
                onProgress(80, "Configuring environment...")
                setupRootfsDirectories()

                onProgress(100, "Environment ready!")
                Log.d(TAG, "Proot environment setup complete")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup proot environment", e)
                throw IOException("Environment setup failed: ${e.message}", e)
            }
        }

    /**
     * Build a proot command that executes the given command inside the Alpine rootfs.
     *
     * @param command The command to run inside proot
     * @param extraBinds Additional bind mounts (host_path -> rootfs_path)
     * @return List of command arguments for ProcessBuilder
     */
    fun buildProotCommand(
        command: List<String>,
        extraBinds: Map<String, String> = emptyMap()
    ): List<String> {
        val prootCmd = mutableListOf<String>()

        // Proot binary
        prootCmd.add(prootBinary.absolutePath)

        // Rootfs
        prootCmd.addAll(listOf("-r", rootfsDir.absolutePath))

        // Bind /dev, /proc, /sys
        prootCmd.addAll(listOf("-b", "/dev"))
        prootCmd.addAll(listOf("-b", "/proc"))
        prootCmd.addAll(listOf("-b", "/sys"))

        // Bind app storage for accessing images
        prootCmd.addAll(listOf("-b", "${filesDir.absolutePath}:/mnt/app"))

        // Bind external storage if available
        val externalStorage = context.getExternalFilesDir(null)
        if (externalStorage != null) {
            prootCmd.addAll(listOf("-b", "${externalStorage.absolutePath}:/mnt/sdcard"))
        }

        // Extra bind mounts
        for ((host, guest) in extraBinds) {
            prootCmd.addAll(listOf("-b", "$host:$guest"))
        }

        // Working directory
        prootCmd.addAll(listOf("-w", "/root"))

        // The command to execute
        prootCmd.add("/bin/sh")
        prootCmd.add("-c")
        prootCmd.add(command.joinToString(" ") { arg ->
            if (arg.contains(" ") || arg.contains("=")) "'$arg'" else arg
        })

        return prootCmd
    }

    /**
     * Build a proot command specifically for running QEMU.
     *
     * @param qemuArgs QEMU command line arguments
     * @param imageDir Directory containing disk images (will be bind-mounted)
     * @return List of command arguments for ProcessBuilder
     */
    fun buildQemuCommand(
        qemuArgs: List<String>,
        imageDir: File
    ): List<String> {
        val fullCommand = mutableListOf("qemu-system-arm")
        fullCommand.addAll(qemuArgs)

        return buildProotCommand(
            command = fullCommand,
            extraBinds = mapOf(
                imageDir.absolutePath to "/mnt/images"
            )
        )
    }

    /**
     * Get the path where images should be stored, accessible from within proot.
     */
    fun getImageHostDir(): File {
        val dir = File(filesDir, "images")
        dir.mkdirs()
        return dir
    }

    /**
     * Get the path to images as seen from within the proot environment.
     */
    fun getImageProotPath(): String = "/mnt/images"

    private fun extractAsset(assetName: String, targetFile: File) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun extractTarball(tarball: File, targetDir: File) {
        val process = ProcessBuilder(
            "tar", "xzf", tarball.absolutePath, "-C", targetDir.absolutePath
        ).redirectErrorStream(true).start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val error = process.inputStream.bufferedReader().readText()
            throw IOException("tar extraction failed (exit=$exitCode): $error")
        }
    }

    private fun setupRootfsDirectories() {
        // Create necessary mount points inside rootfs
        listOf("dev", "proc", "sys", "mnt/app", "mnt/images", "mnt/sdcard", "tmp").forEach {
            File(rootfsDir, it).mkdirs()
        }

        // Create a minimal /etc/resolv.conf for DNS
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        if (!resolvConf.exists()) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        }
    }
}
