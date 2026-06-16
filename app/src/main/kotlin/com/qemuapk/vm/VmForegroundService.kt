package com.qemuapk.vm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.qemuapk.MainActivity
import com.qemuapk.QemuApkApplication

/**
 * Foreground service that keeps the QEMU VM process alive when the app
 * is backgrounded. Shows a persistent notification with VM status.
 */
class VmForegroundService : Service() {

    companion object {
        private const val TAG = "VmForegroundService"

        fun start(context: Context, vmName: String) {
            val intent = Intent(context, VmForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VM_NAME, vmName)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VmForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private const val ACTION_START = "com.qemuapk.START_VM_SERVICE"
        private const val ACTION_STOP = "com.qemuapk.STOP_VM_SERVICE"
        private const val EXTRA_VM_NAME = "vm_name"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val vmName = intent.getStringExtra(EXTRA_VM_NAME) ?: "Virtual Machine"
                Log.d(TAG, "Starting foreground service for VM: $vmName")
                startForeground(QemuApkApplication.VM_NOTIFICATION_ID, createNotification(vmName))
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping foreground service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotification(vmName: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VmForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, QemuApkApplication.VM_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(com.qemuapk.R.string.vm_running_notification_title))
            .setContentText("$vmName - ${getString(com.qemuapk.R.string.vm_running_notification_text)}")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(com.qemuapk.R.string.action_stop_vm),
                stopIntent
            )
            .build()
    }
}
