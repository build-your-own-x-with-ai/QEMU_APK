package com.qemuapk.vm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manages downloading, verifying, and organizing guest OS images.
 *
 * Phase 1: Alpine ARM32 Linux rootfs + custom kernel/initrd.
 * Phase 2 (future): Full Android ARM32 system image.
 *
 * Images are downloaded on-demand and stored in context.filesDir/images/.
 */
class GuestImageManager(private val context: Context) {

    companion object {
        private const val TAG = "GuestImageManager"
        private const val IMAGES_DIR = "images"
        private const val MANIFEST_FILE = "manifest.json"

        /** Default image set — Alpine ARM32 + kernel from GitHub Releases */
        private const val DEFAULT_KERNEL_RELEASE_URL =
            "https://github.com/YOUR_REPO/QEMU_APK/releases/latest/download"

        /** Alpine ARM32 minirootfs — direct from Alpine CDN */
        private const val ALPINE_ARM32_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/armhf/alpine-minirootfs-3.20.3-armhf.tar.gz"

        private const val ALPINE_ARM32_SIZE = 3_200_000L // ~3.2 MB compressed
    }

    private val imagesDir: File = File(context.filesDir, IMAGES_DIR).also { it.mkdirs() }

    private val _imageStatus = mutableMapOf<String, ImageStatus>()

    /**
     * Check if all required guest images are present and valid.
     */
    fun areImagesReady(): Boolean {
        val kernel = File(imagesDir, "zImage")
        val initrd = File(imagesDir, "initrd")
        val rootfs = File(imagesDir, "alpine-arm32.tar.gz")
        return kernel.exists() && kernel.length() > 0 &&
               initrd.exists() && initrd.length() > 0 &&
               rootfs.exists() && rootfs.length() > 0
    }

    /**
     * Get the current status of each image.
     */
    fun getImageStatuses(): Map<String, ImageStatus> {
        val statuses = mutableMapOf<String, ImageStatus>()

        val requiredImages = listOf(
            "zImage" to 5_000_000L,     // ~5-12 MB kernel
            "initrd" to 1_000_000L,     // ~1-4 MB initramfs
            "alpine-arm32.tar.gz" to ALPINE_ARM32_SIZE
        )

        for ((name, minSize) in requiredImages) {
            val file = File(imagesDir, name)
            statuses[name] = if (file.exists() && file.length() >= minSize) {
                ImageStatus(name, true, file.length(), null)
            } else {
                ImageStatus(name, false, if (file.exists()) file.length() else 0L, null)
            }
        }

        _imageStatus.clear()
        _imageStatus.putAll(statuses)
        return statuses
    }

    /**
     * Get total download size of all images.
     */
    fun getTotalDownloadSize(): Long {
        return imagesDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Download all required guest images.
     *
     * @param kernelReleaseUrl Base URL for kernel/initrd (GitHub Releases)
     * @param onProgress Callback: (percentOverall, currentFileName, bytesDownloaded, bytesTotal)
     */
    suspend fun downloadAllImages(
        kernelReleaseUrl: String = DEFAULT_KERNEL_RELEASE_URL,
        onProgress: (Int, String, Long, Long) -> Unit = { _, _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val downloads = listOf(
            DownloadItem("zImage", "$kernelReleaseUrl/zImage", null),
            DownloadItem("initrd", "$kernelReleaseUrl/initrd", null),
            DownloadItem("alpine-arm32.tar.gz", ALPINE_ARM32_URL, null)
        )

        val totalFiles = downloads.size
        for ((index, item) in downloads.withIndex()) {
            val targetFile = File(imagesDir, item.name)

            // Skip if already downloaded and valid
            if (targetFile.exists() && targetFile.length() > 1000) {
                Log.d(TAG, "Skipping ${item.name} — already exists (${targetFile.length()} bytes)")
                val overallPercent = ((index + 1) * 100) / totalFiles
                onProgress(overallPercent, item.name, targetFile.length(), targetFile.length())
                continue
            }

            onProgress((index * 100) / totalFiles, item.name, 0L, 0L)

            try {
                downloadFile(
                    url = item.url,
                    targetFile = targetFile,
                    expectedSha256 = item.sha256
                ) { bytesDownloaded, bytesTotal ->
                    val filePercent = if (bytesTotal > 0) {
                        (bytesDownloaded * 100 / bytesTotal).toInt()
                    } else 0
                    val overallPercent = ((index * 100) + filePercent) / totalFiles
                    onProgress(overallPercent, item.name, bytesDownloaded, bytesTotal)
                }
                Log.d(TAG, "Downloaded ${item.name}: ${targetFile.length()} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download ${item.name}", e)
                throw IOException("Failed to download ${item.name}: ${e.message}", e)
            }
        }

        Log.d(TAG, "All images downloaded successfully")
    }

    /**
     * Delete all downloaded images (for re-download or cleanup).
     */
    fun deleteAllImages() {
        imagesDir.listFiles()?.forEach { file ->
            if (file.name != MANIFEST_FILE) {
                file.delete()
                Log.d(TAG, "Deleted image: ${file.name}")
            }
        }
    }

    /**
     * Get the images directory.
     */
    fun getImagesDir(): File = imagesDir

    /**
     * Get the absolute path for a specific image.
     */
    fun getImagePath(name: String): String = File(imagesDir, name).absolutePath

    /**
     * Download a single file with progress reporting.
     */
    private fun downloadFile(
        url: String,
        targetFile: File,
        expectedSha256: String?,
        onProgress: (Long, Long) -> Unit
    ) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "QemuApk/1.0")

            // Support resume
            if (tempFile.exists() && tempFile.length() > 0) {
                connection.setRequestProperty("Range", "bytes=${tempFile.length()}-")
            }

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299 && responseCode != 206) {
                throw IOException("HTTP $responseCode downloading $url")
            }

            val totalBytes = if (responseCode == 206) {
                // Partial content: add existing to content-length
                tempFile.length() + (connection.contentLengthLong.takeIf { it > 0 } ?: 0L)
            } else {
                connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            }

            connection.inputStream.use { input ->
                FileOutputStream(tempFile, responseCode == 206).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloadedBytes = if (responseCode == 206) tempFile.length() else 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }

            // Verify checksum if provided
            if (expectedSha256 != null) {
                val actualSha256 = sha256(tempFile)
                if (actualSha256 != expectedSha256) {
                    tempFile.delete()
                    throw IOException(
                        "SHA-256 mismatch for ${targetFile.name}: expected $expectedSha256, got $actualSha256"
                    )
                }
            }

            // Atomic rename
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            // Keep temp file for resume
            throw e
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    data class DownloadItem(
        val name: String,
        val url: String,
        val sha256: String?
    )

    data class ImageStatus(
        val name: String,
        val ready: Boolean,
        val sizeBytes: Long,
        val error: String?
    )
}
