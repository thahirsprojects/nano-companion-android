
package com.nanocompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

class NanoBleService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.nanocompanion.CONNECT"
        const val ACTION_DISCONNECT = "com.nanocompanion.DISCONNECT"
        const val ACTION_SEND_COMMAND = "com.nanocompanion.SEND_COMMAND"
        const val ACTION_FORWARD_NOTIFICATION =
            "com.nanocompanion.FORWARD_NOTIFICATION"

        const val EXTRA_COMMAND = "command"

        private const val ACTION_MUSIC_CONTROL =
            "com.nanocompanion.MUSIC_CONTROL"

        private const val EXTRA_CONTROL = "control"

        private const val CHANNEL_ID = "nano_connection"
        private const val NOTIFICATION_ID = 71
        private const val TAG = "NanoBleService"

        private val SERVICE_UUID: UUID =
            UUID.fromString("7a1c0001-5e0d-4b8e-9f3a-2d6c1b4a0001")

        private val CHARACTERISTIC_UUID: UUID =
            UUID.fromString("7a1c0002-5e0d-4b8e-9f3a-2d6c1b4a0001")

        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null

    @Volatile private var scanning = false
    @Volatile private var connected = false
    @Volatile private var shouldReconnect = true

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false
    private var lastScanStart = 0L

    private val forwardingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(EXTRA_COMMAND)?.let {
                enqueueCommand(it)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        val filter = IntentFilter(ACTION_FORWARD_NOTIFICATION)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                forwardingReceiver,
                filter,
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(forwardingReceiver, filter)
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Waiting to connect to Nano")
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                shouldReconnect = false
                stopScan()

                val oldGatt = gatt
                gatt = null
                characteristic = null
                connected = false

                try {
                    oldGatt?.disconnect()
                    oldGatt?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing BLE", e)
                }

                stopSelf()
            }

            ACTION_SEND_COMMAND -> {
                intent.getStringExtra(EXTRA_COMMAND)?.let {
                    enqueueCommand(it)
                }
            }

            ACTION_CONNECT, null -> {
                shouldReconnect = true
                beginScan()
            }
        }

        return START_STICKY
    }

    private fun beginScan() {
        if (!shouldReconnect || connected || scanning) return

        try {
            val manager =
                getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter

            if (adapter == null || !adapter.isEnabled) {
                updateNotification("Bluetooth is off. Enable Bluetooth.")
                scheduleRescan()
                return
            }

            scanner = adapter.bluetoothLeScanner

            if (scanner == null) {
                updateNotification("BLE scanner unavailable")
                scheduleRescan()
                return
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner?.startScan(null, settings, scanCallback)
            scanning = true
            lastScanStart = SystemClock.elapsedRealtime()

            updateNotification("Searching for Nano…")
            Log.i(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing", e)
            updateNotification("Bluetooth permission required")
        } catch (e: Exception) {
            Log.e(TAG, "BLE scan failed", e)
            scheduleRescan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try {
                result.device.name ?: result.scanRecord?.deviceName
            } catch (_: SecurityException) {
                null
            }

            val serviceFound =
                result.scanRecord?.serviceUuids?.any {
                    it.uuid == SERVICE_UUID
                } == true

            if (name.equals("Nano", true) || serviceFound) {
                stopScan()
                connectGatt(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.e(TAG, "BLE scan error $errorCode")
            updateNotification("Scan error $errorCode; retrying")
            scheduleRescan()
        }
    }

    private fun connectGatt(device: BluetoothDevice) {
        updateNotification("Connecting to Nano…")

        try {
            gatt?.close()

            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(
                    this,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                device.connectGatt(this, false, gattCallback)
            }

            if (gatt == null) {
                updateNotification("Connection unavailable; retrying")
                scheduleRescan()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not connect", e)
            scheduleRescan()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                connected = true
                gatt = g
                updateNotification("Connected; discovering services…")

                try {
                    if (!g.discoverServices()) {
                        g.disconnect()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Service discovery failed", e)
                    g.disconnect()
                }
            } else {
                connected = false
                characteristic = null

                synchronized(writeQueue) {
                    writing = false
                    writeQueue.clear()
                }

                try {
                    g.close()
                } catch (_: Exception) {
                }

                if (gatt == g) gatt = null

                updateNotification("Disconnected; reconnecting")
                if (shouldReconnect) scheduleRescan()
            }
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                g.disconnect()
                return
            }

            val service: BluetoothGattService? =
                g.getService(SERVICE_UUID)
            val ch = service?.getCharacteristic(CHARACTERISTIC_UUID)

            if (ch == null) {
                updateNotification("Nano characteristic not found")
                g.disconnect()
                return
            }

            characteristic = ch

            // Subscribe to ESP32 notifications for commands such as C|NEXT.
            val supportsNotify =
                ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

            if (!supportsNotify) {
                updateNotification("Nano firmware needs BLE notify support")
                Log.e(TAG, "Characteristic has no NOTIFY property")
                return
            }

            try {
                if (!g.setCharacteristicNotification(ch, true)) {
                    updateNotification("Could not enable Nano notifications")
                    return
                }

                val descriptor = ch.getDescriptor(CCCD_UUID)

                if (descriptor == null) {
                    updateNotification("BLE notification descriptor missing")
                    return
                }

                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value =
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }

                if (!ok) {
                    updateNotification("Could not subscribe to Nano")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Notification subscription failed", e)
                return
            }

            val now = System.currentTimeMillis() / 1000L
            val offset = java.util.TimeZone.getDefault()
                .getOffset(System.currentTimeMillis()) / 1000

            enqueueCommand("T|$now|$offset")
            updateNotification("Connected to Nano")
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Subscribed to Nano notifications")
                pumpWrites()
            } else {
                Log.e(TAG, "CCCD subscription failed: $status")
            }
        }

        @Deprecated("Deprecated on newer Android versions")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            handleNanoMessage(ch.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNanoMessage(value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            synchronized(writeQueue) {
                writing = false
                if (writeQueue.isNotEmpty()) writeQueue.removeFirst()
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "BLE write failed: $status")
            }

            pumpWrites()
        }
    }

    private fun handleNanoMessage(bytes: ByteArray) {
        val message = bytes.toString(Charsets.UTF_8).trim()
        Log.i(TAG, "Nano -> phone: $message")

        if (!message.startsWith("C|")) return

        val control = message.substringAfter("C|").trim().uppercase()

        if (control !in setOf("NEXT", "PREV", "PLAYPAUSE")) return

        sendBroadcast(
            Intent(ACTION_MUSIC_CONTROL)
                .setPackage(packageName)
                .putExtra(EXTRA_CONTROL, control)
        )
    }

    private fun enqueueCommand(command: String) {
        if (command.isBlank()) return

        synchronized(writeQueue) {
            if (writeQueue.size >= 100) writeQueue.removeFirst()
            writeQueue.addLast(command.toByteArray(Charsets.UTF_8))
        }

        pumpWrites()
    }

    private fun pumpWrites() {
        val g = gatt ?: return
        val ch = characteristic ?: return

        val bytes: ByteArray

        synchronized(writeQueue) {
            if (writing || writeQueue.isEmpty() || !connected) return
            bytes = writeQueue.first()
            writing = true
        }

        try {
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(
                    ch,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                @Suppress("DEPRECATION")
                ch.value = bytes

                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }

            if (!ok) {
                synchronized(writeQueue) { writing = false }
                Log.e(TAG, "Could not start BLE write")
            }
        } catch (e: Exception) {
            synchronized(writeQueue) { writing = false }
            Log.e(TAG, "BLE write failed", e)
        }
    }

    private fun stopScan() {
        if (!scanning) return

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }

        scanning = false
    }

    private fun scheduleRescan() {
        if (!shouldReconnect) return

        Thread {
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                return@Thread
            }

            if (shouldReconnect && !connected && !scanning) beginScan()
        }.start()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nano connection",
                NotificationManager.IMPORTANCE_LOW
            )

            (getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Nano Companion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager).notify(
                NOTIFICATION_ID,
                buildNotification(text)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed", e)
        }
    }

    override fun onDestroy() {
        shouldReconnect = false
        stopScan()

        try {
            unregisterReceiver(forwardingReceiver)
        } catch (_: Exception) {
        }

        val oldGatt = gatt
        gatt = null
        characteristic = null
        connected = false

        try {
            oldGatt?.disconnect()
            oldGatt?.close()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
