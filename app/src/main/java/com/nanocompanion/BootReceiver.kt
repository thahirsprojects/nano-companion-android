package com.nanocompanion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

// Reconnects to Nano after the phone restarts or the app is updated,
// when "Reconnect automatically" is enabled.
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!NanoPrefs(context).startOnBoot) return

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        try {
            context.startForegroundService(
                Intent(context, NanoBleService::class.java)
                    .setAction(NanoBleService.ACTION_CONNECT)
            )
        } catch (e: Exception) {
            Log.w("NanoBoot", "Could not start Nano service", e)
        }
    }
}
