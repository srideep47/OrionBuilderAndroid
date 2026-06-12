package com.legion.orion.runtime.buildtools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives PackageInstaller session status. A sideloaded APK requires explicit user confirmation, so
 * on STATUS_PENDING_USER_ACTION we launch the system confirm activity the installer hands us.
 */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.w(TAG, "could not launch install confirm", it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "install succeeded")
            else -> Log.w(TAG, "install status=$status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }

    companion object {
        private const val TAG = "ApkInstallReceiver"
        const val ACTION = "com.legion.orion.runtime.buildtools.INSTALL_STATUS"
    }
}
