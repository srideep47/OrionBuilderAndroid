package com.legion.orion.runtime.buildtools

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installs a locally built APK through a PackageInstaller session (sideload; user confirms). */
class ApkInstaller(private val context: Context) {

    suspend fun install(apk: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(apk.isFile) { "APK not found: $apk" }
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, ApkInstallReceiver::class.java).setAction(ApkInstallReceiver.ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        }
    }
}
