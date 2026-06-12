package com.legion.orion.infra.p2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Toggleable foreground host for P2P participation. Holds a foreground slot so the in-process
 * bridge (in the :node process) keeps a live network presence while the app is backgrounded, and
 * grabs a Wi-Fi [WifiManager.MulticastLock] so LAN discovery broadcasts (UDP :38291) are actually
 * delivered to the socket on Wi-Fi — without it many devices drop non-unicast packets.
 *
 * The bridge's own start/stop is driven by P2pController from the app layer; this service only
 * manages OS-level presence. Battery policy (throttle on battery / full while charging) is layered
 * on top by the caller deciding when to start/stop it.
 */
class P2pService : Service() {

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireMulticastLock()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { multicastLock?.release() }
        multicastLock = null
        super.onDestroy()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("orion-p2p-lan").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "multicast lock failed", it) }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "P2P network", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Keeps Orion connected to peers" },
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orion network")
            .setContentText("Connected to the Orion peer network")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "P2pService"
        private const val CHANNEL_ID = "orion_p2p"
        private const val NOTIFICATION_ID = 3001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, P2pService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, P2pService::class.java))
        }
    }
}
