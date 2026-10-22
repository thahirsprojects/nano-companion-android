package com.nanocompanion

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val requestCode = 401
    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFFF6F7FB.toInt())
        }
        val scroller = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 12)
        }
        val title = TextView(this).apply {
            text = "Nano Companion"
            textSize = 28f
            setTextColor(0xFF172033.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        val subtitle = TextView(this).apply {
            text = "Bluetooth • Time sync • Phone notifications"
            textSize = 14f
            setTextColor(0xFF586174.toInt())
            setPadding(0, 6, 0, 20)
        }
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF172033.toInt())
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFFFFFFF.toInt())
            text = "Checking status…"
        }
        content.addView(title)
        content.addView(subtitle)
        content.addView(status)
        content.addView(actionButton("Enable permissions and connect to Nano") { ensurePermissionsThenConnect() })
        content.addView(actionButton("Enable notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        content.addView(actionButton("Send test notification") {
            sendCommand("N|Nano Companion|Hello from your Android phone!")
        })
        content.addView(actionButton("Test water reminder") { sendCommand("W") })
        content.addView(actionButton("Sync time now") { sendTimeCommand() })
        content.addView(actionButton("Disconnect Nano") {
            startService(Intent(this, NanoBleService::class.java).setAction(NanoBleService.ACTION_DISCONNECT))
            Toast.makeText(this, "Disconnect requested", Toast.LENGTH_SHORT).show()
        })
        content.addView(TextView(this).apply {
            text = "\nFirst-time setup\n1. Tap Enable permissions and connect.\n2. Tap Enable notification access and allow Nano Companion.\n3. Keep Nano powered on and nearby.\n4. On Samsung, set this app's battery usage to Unrestricted if background reconnection is unreliable.\n\nPrivacy: notification titles and text are forwarded to your Nano. Avoid enabling forwarding on a shared display if notifications may contain private information."
            textSize = 14f
            setTextColor(0xFF586174.toInt())
            setPadding(2, 8, 2, 12)
        })
        scroller.addView(content)
        root.addView(scroller)
        setContentView(root)
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

    private fun ensurePermissionsThenConnect() {
        val missing = requiredPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), requestCode)
        } else {
            connect()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) connect()
            else Toast.makeText(this, "Bluetooth/notification permissions are needed for the requested features.", Toast.LENGTH_LONG).show()
        }
    }

    private fun connect() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        if (adapter == null) {
            Toast.makeText(this, "This phone does not support Bluetooth.", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val intent = Intent(this, NanoBleService::class.java).setAction(NanoBleService.ACTION_CONNECT)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Connecting to Nano…\nThe app will sync time when connected."
    }

    private fun sendCommand(command: String) {
        val intent = Intent(this, NanoBleService::class.java)
            .setAction(NanoBleService.ACTION_SEND_COMMAND)
            .putExtra(NanoBleService.EXTRA_COMMAND, command)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Command queued if Nano is connected.", Toast.LENGTH_SHORT).show()
    }

    private fun sendTimeCommand() {
        val now = System.currentTimeMillis() / 1000L
        val offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        sendCommand("T|$now|$offset")
    }

    private fun refreshStatus() {
        status.text = "Device: Nano\nBLE: start a connection below\nTime: synced automatically after connection\nNotifications: ${if (isNotificationAccessEnabled()) "access enabled" else "access not enabled yet"}"
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }
}
