package com.qemuapk.vm

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.UUID

/**
 * Configuration for a virtual machine instance.
 * Persisted as JSON in the app's private storage.
 */
data class VmConfig(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("name")
    val name: String = "Android ARM32",

    @SerializedName("ram_mb")
    val ramMb: Int = 1024,

    @SerializedName("cpu_cores")
    val cpuCores: Int = 2,

    @SerializedName("display_width")
    val displayWidth: Int = 720,

    @SerializedName("display_height")
    val displayHeight: Int = 1280,

    @SerializedName("system_image_path")
    val systemImagePath: String = "",

    @SerializedName("userdata_image_path")
    val userdataImagePath: String = "",

    @SerializedName("cache_image_path")
    val cacheImagePath: String = "",

    @SerializedName("kernel_path")
    val kernelPath: String = "",

    @SerializedName("initrd_path")
    val initrdPath: String = "",

    @SerializedName("shared_folder_path")
    val sharedFolderPath: String = "",

    @SerializedName("enable_kvm")
    val enableKvm: Boolean = false,

    @SerializedName("vnc_port")
    val vncPort: Int = 5900,

    @SerializedName("adb_host_port")
    val adbHostPort: Int = 5555,

    @SerializedName("guest_boot_args")
    val guestBootArgs: String = "console=ttyAMA0 androidboot.hardware=cuttlefish androidboot.selinux=permissive",

    @SerializedName("image_version")
    val imageVersion: String = ""
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): VmConfig = gson.fromJson(json, VmConfig::class.java)

        fun fromFile(file: File): VmConfig = fromJson(file.readText())

        fun createDefault(basePath: String): VmConfig {
            return VmConfig(
                systemImagePath = "$basePath/system.img",
                userdataImagePath = "$basePath/userdata.qcow2",
                cacheImagePath = "$basePath/cache.img",
                kernelPath = "$basePath/zImage",
                initrdPath = "$basePath/ramdisk.img",
                sharedFolderPath = "$basePath/shared"
            )
        }
    }

    fun toJson(): String = gson.toJson(this)

    fun saveToFile(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toJson())
    }
}
