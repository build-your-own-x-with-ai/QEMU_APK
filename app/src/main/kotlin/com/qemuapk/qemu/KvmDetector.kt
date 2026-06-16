package com.qemuapk.qemu

import android.util.Log
import java.io.File

/**
 * Detects KVM (Kernel-based Virtual Machine) availability on the host device.
 * KVM enables near-native speed for the guest VM but is typically unavailable
 * on unrooted Android devices.
 */
object KvmDetector {

    private const val TAG = "KvmDetector"
    private const val KVM_DEVICE_PATH = "/dev/kvm"

    /**
     * Check if KVM is available and accessible.
     * @return true if /dev/kvm exists and is readable/writable
     */
    fun isKvmAvailable(): Boolean {
        return try {
            val kvmDevice = File(KVM_DEVICE_PATH)
            val exists = kvmDevice.exists()
            val canRead = kvmDevice.canRead()
            val canWrite = kvmDevice.canWrite()

            Log.d(TAG, "KVM device: exists=$exists, canRead=$canRead, canWrite=$canWrite")
            exists && canRead && canWrite
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot access KVM device: ${e.message}")
            false
        }
    }

    /**
     * Get the recommended acceleration mode based on KVM availability.
     * @return "kvm" if available, "tcg" (software emulation) otherwise
     */
    fun getRecommendedAccel(): String {
        return if (isKvmAvailable()) "kvm" else "tcg"
    }

    /**
     * Get a human-readable status string for KVM availability.
     */
    fun getStatusDescription(): String {
        return if (isKvmAvailable()) {
            "KVM available - hardware acceleration enabled"
        } else {
            "KVM not available - using software emulation (TCG). " +
                "Performance will be significantly reduced. " +
                "Root access may be required for KVM support."
        }
    }
}
