package com.legion.orion.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground host that keeps the loaded MNN model and (later) the Ktor server alive while the
 * app is backgrounded. The model itself lives in the injected [InferenceController] singleton;
 * this service exists only to claim a foreground slot so Android won't reclaim the process
 * mid-generation.
 */
@AndroidEntryPoint
class InferenceService : Service() {

    @Inject lateinit var controller: InferenceController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Local inference",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Keeps the local AI model loaded" },
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orion engine")
            .setContentText("Local model active")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "orion_inference"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, InferenceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InferenceService::class.java))
        }
    }
}
