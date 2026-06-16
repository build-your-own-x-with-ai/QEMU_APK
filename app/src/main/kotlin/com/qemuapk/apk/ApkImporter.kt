package com.qemuapk.apk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Handles importing APK files from the host device storage
 * into the app's private directory for installation into the guest VM.
 */
class ApkImporter(private val context: Context) {

    companion object {
        private const val TAG = "ApkImporter"
        private const val APK_DIR = "imported_apks"
    }

    private val apkDir: File = File(context.filesDir, APK_DIR).also { it.mkdirs() }

    /**
     * Create an intent to pick an APK file from the host device.
     */
    fun createPickApkIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
            // Also allow any file type with .apk extension
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.android.package-archive",
                "application/octet-stream"
            ))
        }
    }

    /**
     * Import an APK from a content URI into the app's private storage.
     *
     * @param uri Content URI of the APK file
     * @return ImportedApkInfo with details about the imported APK
     */
    suspend fun importApk(uri: Uri): ImportedApkInfo = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open APK file")

            // Generate a unique filename
            val fileName = generateApkFileName(uri)
            val targetFile = File(apkDir, fileName)

            inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val sizeBytes = targetFile.length()
            Log.d(TAG, "Imported APK: ${targetFile.absolutePath} (${sizeBytes} bytes)")

            ImportedApkInfo(
                localFile = targetFile,
                originalName = getFileNameFromUri(uri) ?: fileName,
                sizeBytes = sizeBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import APK", e)
            throw IOException("Failed to import APK: ${e.message}", e)
        }
    }

    /**
     * List all imported APK files.
     */
    fun listImportedApks(): List<ImportedApkInfo> {
        return apkDir.listFiles()
            ?.filter { it.extension == "apk" || it.name.endsWith(".apk") }
            ?.map { file ->
                ImportedApkInfo(
                    localFile = file,
                    originalName = file.name,
                    sizeBytes = file.length()
                )
            } ?: emptyList()
    }

    /**
     * Delete an imported APK file.
     */
    fun deleteImportedApk(file: File): Boolean {
        return file.delete()
    }

    /**
     * Get the directory where imported APKs are stored.
     */
    fun getApkDirectory(): File = apkDir

    private fun generateApkFileName(uri: Uri): String {
        val originalName = getFileNameFromUri(uri)
        if (originalName != null && originalName.endsWith(".apk", ignoreCase = true)) {
            return originalName
        }
        // Generate timestamp-based name
        return "imported_${System.currentTimeMillis()}.apk"
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        return name
    }
}

/**
 * Information about an imported APK file.
 */
data class ImportedApkInfo(
    val localFile: File,
    val originalName: String,
    val sizeBytes: Long
) {
    val sizeMb: Float get() = sizeBytes / (1024f * 1024f)
    val sizeFormatted: String get() = "%.1f MB".format(sizeMb)
}
