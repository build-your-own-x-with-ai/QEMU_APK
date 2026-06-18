package com.qemuapk.vm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

        /** Asset paths for bundled kernel images */
        private const val ASSET_ZIMAGE = "guest/zImage"
        private const val ASSET_INITRD = "guest/initrd"

        /** Alpine ARM32 minirootfs — direct from Alpine CDN */
        private const val ALPINE_ARM32_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/armhf/alpine-minirootfs-3.20.3-armhf.tar.gz"
    }

    private val imagesDir: File = File(context.filesDir, IMAGES_DIR).also { it.mkdirs() }

    private val _imageStatus = mutableMapOf<String, ImageStatus>()

    /**
     * Check if all required guest images are present and valid.
     * Kernel and initrd come from bundled assets; Alpine rootfs is downloaded.
     */
    fun areImagesReady(): Boolean {
        val kernel = File(imagesDir, "zImage")
        val initrd = File(imagesDir, "initrd")
        return kernel.exists() && kernel.length() > 100_000 &&
               initrd.exists() && initrd.length() > 0
    }

    /**
     * Extract bundled kernel images from APK assets.
     * Called automatically during setup — no download needed for zImage/initrd.
     */
    suspend fun extractBundledImages() = withContext(Dispatchers.IO) {
        val zImageFile = File(imagesDir, "zImage")
        val initrdFile = File(imagesDir, "initrd")

        if (!zImageFile.exists() || zImageFile.length() < 100_000) {
            Log.d(TAG, "Extracting bundled zImage from assets")
            context.assets.open(ASSET_ZIMAGE).use { input ->
                FileOutputStream(zImageFile).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "zImage extracted: ${zImageFile.length()} bytes")
        }

        if (!initrdFile.exists() || initrdFile.length() == 0L) {
            Log.d(TAG, "Extracting bundled initrd from assets")
            context.assets.open(ASSET_INITRD).use { input ->
                FileOutputStream(initrdFile).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "initrd extracted: ${initrdFile.length()} bytes")
        }
    }

    /**
     * Get the current status of each image.
     */
    fun getImageStatuses(): Map<String, ImageStatus> {
        val statuses = mutableMapOf<String, ImageStatus>()

        val requiredImages = listOf(
            "zImage" to 5_000_000L,     // ~5-12 MB kernel
            "initrd" to 100L,           // initramfs (any size > 0)
            "alpine-arm32.tar.gz" to 100_000L  // Alpine rootfs
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
     * Download Alpine ARM32 rootfs (the only image not bundled in APK).
     * zImage and initrd are extracted from assets via extractBundledImages().
     */
    suspend fun downloadAllImages(
        kernelReleaseUrl: String = "",
        onProgress: (Int, String, Long, Long) -> Unit = { _, _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        // First extract bundled kernel images
        extractBundledImages()
        onProgress(33, "zImage + initrd (bundled)", 1, 1)

        // Download Alpine ARM32 rootfs
        val rootfsFile = File(imagesDir, "alpine-arm32.tar.gz")
        if (rootfsFile.exists() && rootfsFile.length() > 100_000) {
            Log.d(TAG, "Alpine rootfs already exists: ${rootfsFile.length()} bytes")
            onProgress(100, "alpine-arm32.tar.gz (cached)", rootfsFile.length(), rootfsFile.length())
            return@withContext
        }

        onProgress(50, "alpine-arm32.tar.gz", 0L, 0L)
        try {
            downloadFile(
                url = ALPINE_ARM32_URL,
                targetFile = rootfsFile,
                expectedSha256 = null
            ) { bytesDownloaded, bytesTotal ->
                val filePercent = if (bytesTotal > 0) (bytesDownloaded * 100 / bytesTotal).toInt() else 0
                val overallPercent = 50 + (filePercent / 2)
                onProgress(overallPercent, "alpine-arm32.tar.gz", bytesDownloaded, bytesTotal)
            }
            Log.d(TAG, "Alpine ARM32 rootfs downloaded: ${rootfsFile.length()} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download Alpine rootfs", e)
            throw IOException("Failed to download Alpine ARM32 rootfs: ${e.message}", e)
        }

        onProgress(100, "Done", 1, 1)
        Log.d(TAG, "All images ready")
    }

    /**
     * Delete all downloaded images (for re-download or cleanup).
     */
    fun deleteAllImages() {
        imagesDir.listFiles()?.forEach { file ->
            file.delete()
            Log.d(TAG, "Deleted image: ${file.name}")
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

    data class ImageStatus(
        val name: String,
        val ready: Boolean,
        val sizeBytes: Long,
        val error: String?
    )
}
