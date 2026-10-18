package com.xa.installer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirmation != null) try { context.startActivity(confirmation) } catch (_: Exception) { }
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "xa-install"
        manager.createNotificationChannel(NotificationChannel(channelId, "Installation results", NotificationManager.IMPORTANCE_DEFAULT))
        val success = status == PackageInstaller.STATUS_SUCCESS
        val message = if (success) "Installation completed" else "Installation failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status}"
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (success) "XA · Installed" else "XA · Installation error")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        try { manager.notify(System.currentTimeMillis().toInt(), notification) } catch (_: SecurityException) { }
    }
}