
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

        private const val CHANNEL_ID = "nano_connection"
        private const val NOTIFICATION_ID = 71
        private const val TAG = "NanoBleService"

        private val SERVICE_UUID: UUID =
            UUID.fromString("7a1c0001-5e0d-4b8e-9f3a-2d6c1b4a0001")

        private val CHARACTERISTIC_UUID: UUID =
            UUID.fromString("7a1c0002-5e0d-4b8e-9f3a-2d6c1b4a0001")
    }

    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null

    @Volatile
    private var scanning = false

    @Volatile
    private var connected = false

    @Volatile
    private var shouldReconnect = true

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    private val mainHandler =
        android.os.Handler(android.os.Looper.getMainLooper())

    private var receiverRegistered = false
    private var scanGeneration = 0

    private val forwardingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getStringExtra(EXTRA_COMMAND) ?: return
            enqueueCommand(command)
        }
    }

    // Start foreground mode before receiver registration or BLE work.
    override fun onCreate() {
        super.onCreate()

        try {
            createChannel()

            startForeground(
                NOTIFICATION_ID,
                buildNotification("Starting Nano connection…")
            )

            Log.d(TAG, "Foreground service started")

            registerForwardingReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed", e)
            stopSelf()
        }
    }

    private fun registerForwardingReceiver() {
        if (receiverRegistered) return

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

        receiverRegistered = true
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        // Ensure foreground status before handling incoming actions.
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Starting Nano connection…")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not enter foreground mode", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_DISCONNECT -> {
                shouldReconnect = false
                stopScan()

                try {
                    gatt?.disconnect()
                    gatt?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing BLE connection", e)
                }

                gatt = null
                characteristic = null
                connected = false

                synchronized(writeQueue) {
                    writeQueue.clear()
                    writing = false
                }

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
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

            else -> {
                Log.w(TAG, "Unknown service action: ${intent.action}")
            }
        }

        return START_STICKY
    }

    private fun beginScan() {
        if (!shouldReconnect || connected || scanning) return

        try {
            val manager =
                getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            val adapter: BluetoothAdapter = manager.adapter
                ?: run {
                    updateNotification("Bluetooth is unavailable")
                    scheduleRescan()
                    return
                }

            if (!adapter.isEnabled) {
                updateNotification("Enable Bluetooth to connect to Nano")
                scheduleRescan()
                return
            }

            val bleScanner = adapter.bluetoothLeScanner
                ?: run {
                    updateNotification("Bluetooth scanner unavailable")
                    scheduleRescan()
                    return
                }

            scanner = bleScanner

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()

            bleScanner.startScan(null, settings, scanCallback)

            scanning = true
            updateNotification("Searching for Nano…")

            Log.d(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth scan permission missing", e)
            updateNotification("Bluetooth permission required")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start BLE scan", e)
            updateNotification("Scan failed; retrying")
            scheduleRescan()
        }
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            if (!shouldReconnect || connected) return

            val name = try {
                result.device.name ?: result.scanRecord?.deviceName
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot read Bluetooth device name", e)
                null
            }

            val hasService = result.scanRecord
                ?.serviceUuids
                ?.any { it.uuid == SERVICE_UUID } == true

            if (name.equals("Nano", ignoreCase = true) || hasService) {
                Log.d(TAG, "Nano found")

                stopScan()
                connectGatt(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false

            Log.e(TAG, "BLE scan failed: $errorCode")

            updateNotification("Scan failed; retrying")
            scheduleRescan()
        }
    }

    private fun connectGatt(device: BluetoothDevice) {
        if (!shouldReconnect) return

        updateNotification("Connecting to Nano…")
        Log.d(TAG, "Connecting to Nano GATT")

        try {
            gatt?.close()
            gatt = null

            connected = false
            characteristic = null

            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(
                    this,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(this, false, gattCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth connect permission missing", e)
            updateNotification("Bluetooth permission required")
            scheduleRescan()
        } catch (e: Exception) {
            Log.e(TAG, "GATT connection failed", e)
            updateNotification("Connection failed; retrying")
            scheduleRescan()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            Log.d(
                TAG,
                "Connection state: status=$status state=$newState"
            )

            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                gatt = g
                connected = true
                characteristic = null

                updateNotification("Connected to Nano • discovering services")

                try {
                    if (!g.discoverServices()) {
                        Log.w(TAG, "discoverServices returned false")
                        g.disconnect()
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Service discovery permission error", e)
                    g.disconnect()
                }
            } else {
                if (gatt == g) {
                    gatt = null
                }

                connected = false
                characteristic = null

                synchronized(writeQueue) {
                    writeQueue.clear()
                    writing = false
                }

                try {
                    g.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not close GATT", e)
                }

                if (shouldReconnect) {
                    updateNotification("Nano disconnected • reconnecting")
                    scheduleRescan()
                } else {
                    updateNotification("Disconnected from Nano")
                }
            }
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                updateNotification("Service discovery failed; retrying")
                g.disconnect()
                return
            }

            val service: BluetoothGattService? =
                g.getService(SERVICE_UUID)

            val ch = service?.getCharacteristic(CHARACTERISTIC_UUID)

            if (ch == null) {
                Log.e(TAG, "Nano command characteristic not found")
                updateNotification("Nano characteristic not found")
                g.disconnect()
                return
            }

            gatt = g
            characteristic = ch

            Log.d(TAG, "Nano BLE characteristic ready")
            updateNotification("Connected to Nano")

            val now = System.currentTimeMillis() / 1000L
            val offset =
                java.util.TimeZone.getDefault()
                    .getOffset(System.currentTimeMillis()) / 1000

            enqueueCommand("T|$now|$offset")
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            synchronized(writeQueue) {
                writing = false

                if (writeQueue.isNotEmpty()) {
                    writeQueue.removeFirst()
                }
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "BLE write failed: $status")
            }

            pumpWrites()
        }
    }

    private fun enqueueCommand(command: String) {
        if (command.isBlank()) return

        synchronized(writeQueue) {
            if (writeQueue.size >= 100) {
                writeQueue.removeFirst()
            }

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
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(
                    ch,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                @Suppress("DEPRECATION")
                ch.value = bytes

                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }

            if (!started) {
                synchronized(writeQueue) {
                    writing = false
                }

                Log.w(TAG, "BLE write could not be started")
            }
        } catch (e: SecurityException) {
            synchronized(writeQueue) {
                writing = false
            }

            Log.e(TAG, "BLE write permission missing", e)
        } catch (e: Exception) {
            synchronized(writeQueue) {
                writing = false
            }

            Log.e(TAG, "BLE write failed to start", e)
        }
    }

    private fun stopScan() {
        scanGeneration++

        if (!scanning) return

        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE scan", e)
        }

        scanning = false
    }

    private fun scheduleRescan() {
        if (!shouldReconnect) return

        val generation = ++scanGeneration

        mainHandler.postDelayed({
            if (
                shouldReconnect &&
                !connected &&
                !scanning &&
                generation == scanGeneration
            ) {
                beginScan()
            }
        }, 3000L)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nano connection",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed", e)
        }
    }

    override fun onDestroy() {
        shouldReconnect = false

        stopScan()

        if (receiverRegistered) {
            try {
                unregisterReceiver(forwardingReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Receiver unregister failed", e)
            }

            receiverRegistered = false
        }

        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "GATT cleanup failed", e)
        }

        gatt = null
        characteristic = null
        connected = false

        synchronized(writeQueue) {
            writeQueue.clear()
            writing = false
        }

        mainHandler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
