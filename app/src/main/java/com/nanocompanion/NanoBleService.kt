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
        const val ACTION_FORWARD_NOTIFICATION = "com.nanocompanion.FORWARD_NOTIFICATION"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL_ID = "nano_connection"
        private const val NOTIFICATION_ID = 71
        private const val TAG = "NanoBleService"
        private val SERVICE_UUID: UUID = UUID.fromString("7a1c0001-5e0d-4b8e-9f3a-2d6c1b4a0001")
        private val CHARACTERISTIC_UUID: UUID = UUID.fromString("7a1c0002-5e0d-4b8e-9f3a-2d6c1b4a0001")
    }

    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var connected = false
    private var shouldReconnect = true
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false
    private var lastScanStart = 0L

    private val forwardingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getStringExtra(EXTRA_COMMAND) ?: return
            enqueueCommand(command)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(forwardingReceiver, IntentFilter(ACTION_FORWARD_NOTIFICATION), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(forwardingReceiver, IntentFilter(ACTION_FORWARD_NOTIFICATION))
        }
        startForeground(NOTIFICATION_ID, buildNotification("Waiting to connect to Nano"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                shouldReconnect = false
                stopScan()
                gatt?.disconnect()
                gatt?.close()
                gatt = null
                characteristic = null
                connected = false
                stopSelf()
            }
            ACTION_SEND_COMMAND -> intent.getStringExtra(EXTRA_COMMAND)?.let { enqueueCommand(it) }
            ACTION_CONNECT, null -> {
                shouldReconnect = true
                beginScan()
            }
        }
        return START_STICKY
    }

    private fun beginScan() {
        if (!shouldReconnect || connected || scanning) return
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            updateNotification("Bluetooth is off. Enable Bluetooth to connect.")
            scheduleRescan()
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            scheduleRescan()
            return
        }
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            scanner?.startScan(null, settings, scanCallback)
            scanning = true
            lastScanStart = SystemClock.elapsedRealtime()
            updateNotification("Searching for Nano…")
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth scan permission missing", e)
            updateNotification("Bluetooth permission required. Open Nano Companion.")
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            scheduleRescan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try { result.device.name ?: result.scanRecord?.deviceName } catch (_: SecurityException) { null }
            if (name.equals("Nano", ignoreCase = true) ||
                result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true) {
                stopScan()
                connectGatt(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.w(TAG, "Scan failed: $errorCode")
            scheduleRescan()
        }
    }

    private fun connectGatt(device: BluetoothDevice) {
        updateNotification("Connecting to Nano…")
        try {
            gatt?.close()
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this, false, gattCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth connect permission missing", e)
            updateNotification("Bluetooth permission required.")
            scheduleRescan()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        
            
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                Log.i(
                    TAG,
                    "BLE connection callback: status=$status, newState=$newState"
                )
            
                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED
                ) {
                    connected = true
                    gatt = g
            
                    updateNotification("Connected; discovering Nano services…")
            
                    try {
                        val started = g.discoverServices()
            
                        Log.i(TAG, "Service discovery started: $started")
            
                        if (!started) {
                            updateNotification("Service discovery failed; retrying")
                            g.disconnect()
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Bluetooth permission missing during discovery", e)
                        updateNotification("Bluetooth permission required")
                        g.disconnect()
                    }
                } else {
                    connected = false
                    characteristic = null
                    writing = false
            
                    synchronized(writeQueue) {
                        writeQueue.clear()
                    }
            
                    Log.e(
                        TAG,
                        "BLE connection failed/disconnected: status=$status, newState=$newState"
                    )
            
                    try {
                        g.close()
                    } catch (_: Exception) {
                        // Ignore errors while closing the connection.
                    }
            
                    if (gatt == g) {
                        gatt = null
                    }
            
                    updateNotification("BLE error $status; retrying")
            
                    if (shouldReconnect) {
                        scheduleRescan()
                    }
                }
            }

        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateNotification("Could not read Nano service; retrying")
                g.disconnect()
                return
            }
            val service: BluetoothGattService? = g.getService(SERVICE_UUID)
            val ch = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (ch == null) {
                updateNotification("Nano command characteristic not found")
                g.disconnect()
                return
            }
            characteristic = ch
            updateNotification("Connected to Nano • time synchronized")
            val now = System.currentTimeMillis() / 1000L
            val offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
            enqueueCommand("T|$now|$offset")
            pumpWrites()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            synchronized(writeQueue) {
                writing = false
                if (writeQueue.isNotEmpty()) writeQueue.removeFirst()
            }
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "BLE write failed: $status")
            pumpWrites()
        }
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
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
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
                Log.w(TAG, "Could not start BLE write")
            }
        } catch (e: SecurityException) {
            synchronized(writeQueue) { writing = false }
            Log.e(TAG, "BLE write permission missing", e)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }

    private fun scheduleRescan() {
        if (!shouldReconnect) return
        Thread {
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            if (shouldReconnect && !connected && !scanning) beginScan()
        }.start()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Nano connection", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("Nano Companion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) { Log.w(TAG, "Notification update failed", e) }
    }

    override fun onDestroy() {
        shouldReconnect = false
        stopScan()
        try { unregisterReceiver(forwardingReceiver) } catch (_: Exception) {}
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
