package com.legion.orion.build

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps Orion at foreground priority while Claude, Gradle, or another long development job runs. */
class DevelopmentWorkService : Service() {
    private val jobs = linkedMapOf<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_ACQUIRE -> if (id != null) jobs[id] = intent.getStringExtra(EXTRA_LABEL) ?: "Development task"
            ACTION_RELEASE -> if (id != null) jobs.remove(id)
        }
        if (jobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            startForeground(NOTIFICATION_ID, notification(jobs.values.last()))
        }
        return START_NOT_STICKY
    }

    private fun notification(label: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Development tasks", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps builds and Claude Code work running when Orion is backgrounded"
                },
            )
        }
        val suffix = if (jobs.size > 1) " · ${jobs.size} active tasks" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Orion is working")
            .setContentText(label + suffix)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "orion_development_work"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_ACQUIRE = "com.legion.orion.DEV_WORK_ACQUIRE"
        private const val ACTION_RELEASE = "com.legion.orion.DEV_WORK_RELEASE"
        private const val EXTRA_ID = "id"
        private const val EXTRA_LABEL = "label"

        fun acquire(context: Context, id: String, label: String) {
            context.startForegroundService(
                Intent(context, DevelopmentWorkService::class.java)
                    .setAction(ACTION_ACQUIRE)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_LABEL, label),
            )
        }

        fun release(context: Context, id: String) {
            context.startService(
                Intent(context, DevelopmentWorkService::class.java)
                    .setAction(ACTION_RELEASE)
                    .putExtra(EXTRA_ID, id),
            )
        }
    }
}
