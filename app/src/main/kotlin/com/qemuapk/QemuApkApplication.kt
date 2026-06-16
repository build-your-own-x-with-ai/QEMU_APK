package com.qemuapk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class QemuApkApplication : Application() {

    companion object {
        const val VM_NOTIFICATION_CHANNEL_ID = "vm_service_channel"
        const val VM_NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            VM_NOTIFICATION_CHANNEL_ID,
            getString(R.string.vm_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the virtual machine running in the background"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
