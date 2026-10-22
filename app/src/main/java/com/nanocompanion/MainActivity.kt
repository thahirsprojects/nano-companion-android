
package com.nanocompanion

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    companion object {
        private const val REQUEST_PERMISSIONS = 401
    }

    private val requiredPermissions: Array<String>
        get() = buildList {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            // Request Location on all supported Android versions
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }

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

        content.addView(
            actionButton("Enable permissions and connect to Nano") {
                ensurePermissionsThenConnect()
            }
        )

        content.addView(
            actionButton("Open app permissions") {
                openAppPermissions()
            }
        )

        content.addView(
            actionButton("Enable notification access") {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
            }
        )

        content.addView(
            actionButton("Send test notification") {
                sendCommand(
                    "N|Nano Companion|Hello from your Android phone!"
                )
            }
        )

        content.addView(
            actionButton("Test water reminder") {
                sendCommand("W")
            }
        )

        content.addView(
            actionButton("Sync time now") {
                sendTimeCommand()
            }
        )

        content.addView(
            actionButton("Disconnect Nano") {
                val intent = Intent(
                    this,
                    NanoBleService::class.java
                ).setAction(NanoBleService.ACTION_DISCONNECT)

                startService(intent)

                Toast.makeText(
                    this,
                    "Disconnect requested",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        content.addView(
            TextView(this).apply {
                text = """
                    
                    First-time setup
                    
                    1. Tap Enable permissions and connect.
                    2. Grant Nearby devices permission.
                    3. Grant Location permission for this diagnostic test.
                    4. Enable notification access if you want phone notifications.
                    5. Keep Nano powered on and nearby.
                    6. Keep Bluetooth and Location switched on during testing.
                    
                    If Nano remains on Searching for Nano, the next step is to inspect BLE scan logs.
                    
                    Privacy: notification titles and text are forwarded to Nano. Avoid enabling forwarding on a shared display if notifications may contain private information.
                """.trimIndent()

                textSize = 14f
                setTextColor(0xFF586174.toInt())
                setPadding(2, 8, 2, 12)
            }
        )

        scroller.addView(content)
        root.addView(scroller)
        setContentView(root)
    }

    private fun actionButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false

            setOnClickListener {
                action()
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
        }
    }

    private fun ensurePermissionsThenConnect() {
        val missingPermissions = requiredPermissions.filter { permission ->
            checkSelfPermission(permission) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        } else {
            connect()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode != REQUEST_PERMISSIONS) return

        val bluetoothPermissionsGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }

        val locationGranted =
            checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!bluetoothPermissionsGranted) {
            Toast.makeText(
                this,
                "Bluetooth/Nearby devices permission is required.",
                Toast.LENGTH_LONG
            ).show()

            refreshStatus()
            return
        }

        if (!locationGranted) {
            Toast.makeText(
                this,
                "Location was not granted. Continuing with Bluetooth; Location is being tested separately.",
                Toast.LENGTH_LONG
            ).show()
        }

        connect()
    }

    private fun connect() {
        try {
            val manager =
                getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            val adapter: BluetoothAdapter? = manager.adapter

            if (adapter == null) {
                Toast.makeText(
                    this,
                    "This phone does not support Bluetooth.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            if (!adapter.isEnabled) {
                startActivity(
                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                )
                return
            }

            val intent = Intent(
                this,
                NanoBleService::class.java
            ).setAction(NanoBleService.ACTION_CONNECT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            status.text =
                "Searching for Nano…\nBluetooth permissions are available.\nLocation: ${
                    if (hasLocationPermission()) "granted" else "not granted"
                }"

        } catch (e: SecurityException) {
            status.text = "Bluetooth permission error: ${e.message}"

            Toast.makeText(
                this,
                "Check Nearby devices permission.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun sendCommand(command: String) {
        try {
            val intent = Intent(
                this,
                NanoBleService::class.java
            )
                .setAction(NanoBleService.ACTION_SEND_COMMAND)
                .putExtra(NanoBleService.EXTRA_COMMAND, command)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            Toast.makeText(
                this,
                "Command queued if Nano is connected.",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not send command: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun sendTimeCommand() {
        val now = System.currentTimeMillis() / 1000L

        val offset = java.util.TimeZone.getDefault()
            .getOffset(System.currentTimeMillis()) / 1000

        sendCommand("T|$now|$offset")
    }

    private fun refreshStatus() {
        val locationText =
            if (hasLocationPermission()) "granted" else "not granted"

        val bluetoothText =
            if (hasBluetoothPermissions()) "granted" else "not granted"

        val notificationAccess =
            if (isNotificationAccessEnabled()) {
                "enabled"
            } else {
                "not enabled"
            }

        status.text = """
            Device: Nano
            Bluetooth permissions: $bluetoothText
            Location permission: $locationText
            BLE: tap the connect button
            Time: syncs after connection
            Notification access: $notificationAccess
        """.trimIndent()
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission()
        }
    }

    private fun openAppPermissions() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        ).apply {
            data = android.net.Uri.parse("package:$packageName")
        }

        startActivity(intent)
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabledListeners.contains(packageName)
    }

    override fun onResume() {
        super.onResume()

        if (::status.isInitialized) {
            refreshStatus()
        }
    }
}
