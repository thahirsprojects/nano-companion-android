package com.nanocompanion

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log

class NanoNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val body = if (!lines.isNullOrEmpty()) lines.joinToString(" ") { it.toString() } else text
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName.substringAfterLast('.')
        }

        // Android's call notifications are also surfaced here when the dialer/phone app posts them.
        val combined = listOf(title, body).filter { it.isNotBlank() }.distinct().joinToString(": ")
        if (combined.isBlank()) return

        val safeApp = appName.replace("|", "/").replace("\n", " ").take(40)
        val safeMessage = combined.replace("|", "/").replace("\r", " ").replace("\n", " ").take(220)
        val command = "N|$safeApp|$safeMessage"
        sendBroadcast(Intent(NanoBleService.ACTION_FORWARD_NOTIFICATION)
            .setPackage(packageName)
            .putExtra(NanoBleService.EXTRA_COMMAND, command))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i("NanoListener", "Notification listener connected")
    }
}
