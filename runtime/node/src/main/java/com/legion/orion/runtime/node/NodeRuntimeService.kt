package com.legion.orion.runtime.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Hosts the embedded Node runtime in the isolated :node process (declared in the manifest). Started
 * by the main process with the path to the Orion host script; Node runs on a dedicated thread so a
 * Node OOM/crash is contained to this process and can't take down the UI or the loaded model.
 */
class NodeRuntimeService : Service() {

    private var nodeThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val script = intent?.getStringExtra(EXTRA_SCRIPT)
        val args = intent?.getStringArrayListExtra(EXTRA_ARGS).orEmpty()
        if (script != null && nodeThread == null) {
            nodeThread = Thread({ NodeRuntime.startBlocking(script, args) }, "node-main").apply { start() }
        }
        // START_REDELIVER_INTENT (not STICKY): if the :node process dies — e.g. a native addon
        // aborts the Node VM — Android restarts the service WITH the original intent (script path),
        // so onStartCommand re-launches Node instead of getting a null intent and idling dead.
        return START_REDELIVER_INTENT
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Node runtime", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Runs the in-app Node dev environment" },
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orion dev runtime")
            .setContentText("Node environment active")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "orion_node"
        private const val NOTIFICATION_ID = 2001
        const val EXTRA_SCRIPT = "script"
        const val EXTRA_ARGS = "args"

        fun start(context: Context, scriptPath: String, args: ArrayList<String> = arrayListOf()) {
            val intent = Intent(context, NodeRuntimeService::class.java).apply {
                putExtra(EXTRA_SCRIPT, scriptPath)
                putStringArrayListExtra(EXTRA_ARGS, args)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodeRuntimeService::class.java))
        }
    }
}
