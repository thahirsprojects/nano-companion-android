
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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
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

        // Signature-level permission guarding the app's internal broadcasts.
        const val INTERNAL_PERMISSION =
            "com.nanocompanion.permission.INTERNAL"

        const val EXTRA_COMMAND = "command"

        private const val CHANNEL_ID = "nano_connection"
        private const val NOTIFICATION_ID = 71
        private const val TAG = "NanoBleService"
        private const val DEVICE_NAME = "Nano"
        private const val REQUESTED_MTU = 517
        private const val WRITE_RETRY_MS = 250L

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

    @Volatile
    private var scanning = false

    @Volatile
    private var connected = false

    @Volatile
    private var shouldReconnect = true

    @Volatile
    private var servicesRequested = false

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    private val mainHandler =
        android.os.Handler(android.os.Looper.getMainLooper())

    private var receiverRegistered = false
    private var scanGeneration = 0

    @Volatile
    private var statusText = "Starting Nano connection…"

    private val forwardingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getStringExtra(EXTRA_COMMAND) ?: return
            enqueueCommand(command)
        }
    }

    // Keep Nano's clock right when the phone's time or timezone changes.
    private var timeReceiverRegistered = false

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            enqueueCommand(timeCommand())
        }
    }

    // Start foreground mode before receiver registration or BLE work.
    override fun onCreate() {
        super.onCreate()

        try {
            createChannel()
            enterForeground()

            Log.d(TAG, "Foreground service started")

            registerForwardingReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed", e)
            stopSelf()
        }
    }

    private fun enterForeground() {
        val notification = buildNotification(statusText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerForwardingReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter(ACTION_FORWARD_NOTIFICATION)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                forwardingReceiver,
                filter,
                INTERNAL_PERMISSION,
                null,
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                forwardingReceiver,
                filter,
                INTERNAL_PERMISSION,
                null
            )
        }

        receiverRegistered = true

        if (!timeReceiverRegistered) {
            val timeFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }

            // These are protected system broadcasts only the system can send.
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(timeReceiver, timeFilter, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(timeReceiver, timeFilter)
            }

            timeReceiverRegistered = true
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        // Ensure foreground status before handling incoming actions.
        // Reuse the current status so a queued command does not reset it.
        try {
            enterForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Could not enter foreground mode", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_DISCONNECT -> {
                shouldReconnect = false
                stopScan()
                closeGatt()
                NanoState.update(NanoState.Link.OFFLINE, "Disconnected")

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SEND_COMMAND -> {
                intent.getStringExtra(EXTRA_COMMAND)?.let {
                    enqueueCommand(it)
                }

                // A fresh service instance started only to send a command
                // should still look for Nano.
                beginScan()
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
        if (!shouldReconnect || connected || scanning || gatt != null) return

        try {
            val manager =
                getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            val adapter: BluetoothAdapter = manager.adapter
                ?: run {
                    updateNotification("Bluetooth is unavailable", NanoState.Link.WAITING)
                    scheduleRescan()
                    return
                }

            if (!adapter.isEnabled) {
                updateNotification("Enable Bluetooth to connect to Nano", NanoState.Link.WAITING)
                scheduleRescan()
                return
            }

            val bleScanner = adapter.bluetoothLeScanner
                ?: run {
                    updateNotification("Bluetooth scanner unavailable", NanoState.Link.WAITING)
                    scheduleRescan()
                    return
                }

            scanner = bleScanner

            // Filtered scans keep working while the screen is off;
            // unfiltered scans are paused by Android in that state.
            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build(),
                ScanFilter.Builder()
                    .setDeviceName(DEVICE_NAME)
                    .build()
            )

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()

            bleScanner.startScan(filters, settings, scanCallback)

            scanning = true
            updateNotification("Searching for Nano…", NanoState.Link.SEARCHING)

            Log.d(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth scan permission missing", e)
            updateNotification("Bluetooth permission required", NanoState.Link.WAITING)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start BLE scan", e)
            updateNotification("Scan failed; retrying", NanoState.Link.WAITING)
            scheduleRescan()
        }
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            if (!shouldReconnect || connected || gatt != null) return

            val name = try {
                result.scanRecord?.deviceName ?: result.device.name
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot read Bluetooth device name", e)
                null
            }

            val hasService = result.scanRecord
                ?.serviceUuids
                ?.any { it.uuid == SERVICE_UUID } == true

            if (name.equals(DEVICE_NAME, ignoreCase = true) || hasService) {
                Log.d(TAG, "Nano found")

                stopScan()
                connectGatt(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false

            Log.e(TAG, "BLE scan failed: $errorCode")

            updateNotification("Scan failed; retrying", NanoState.Link.WAITING)
            scheduleRescan(10_000L)
        }
    }

    private fun connectGatt(device: BluetoothDevice) {
        if (!shouldReconnect) return

        updateNotification("Connecting to Nano…", NanoState.Link.CONNECTING)
        Log.d(TAG, "Connecting to Nano GATT")

        try {
            closeGatt()

            gatt = device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            if (gatt == null) {
                updateNotification("Connection failed; retrying", NanoState.Link.WAITING)
                scheduleRescan()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth connect permission missing", e)
            gatt = null
            updateNotification("Bluetooth permission required", NanoState.Link.WAITING)
            scheduleRescan()
        } catch (e: Exception) {
            Log.e(TAG, "GATT connection failed", e)
            gatt = null
            updateNotification("Connection failed; retrying", NanoState.Link.WAITING)
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
                servicesRequested = false

                updateNotification("Connected to Nano • discovering services", NanoState.Link.CONNECTING)

                // Ask for a large MTU so a full notification fits in one
                // write; service discovery continues in onMtuChanged.
                try {
                    if (!g.requestMtu(REQUESTED_MTU)) {
                        discoverServices(g)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "MTU request permission error", e)
                    discoverServices(g)
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
                    updateNotification("Nano disconnected • reconnecting", NanoState.Link.SEARCHING)
                    scheduleRescan()
                } else {
                    updateNotification("Disconnected from Nano", NanoState.Link.OFFLINE)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed: mtu=$mtu status=$status")
            discoverServices(g)
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                updateNotification("Service discovery failed; retrying", NanoState.Link.WAITING)
                safeDisconnect(g)
                return
            }

            val service: BluetoothGattService? =
                g.getService(SERVICE_UUID)

            val ch = service?.getCharacteristic(CHARACTERISTIC_UUID)

            if (ch == null) {
                Log.e(TAG, "Nano command characteristic not found")
                updateNotification("Nano characteristic not found", NanoState.Link.WAITING)
                safeDisconnect(g)
                return
            }

            gatt = g
            characteristic = ch

            Log.d(TAG, "Nano BLE characteristic ready")
            updateNotification("Connected to Nano", NanoState.Link.CONNECTED)

            synchronized(writeQueue) {
                writeQueue.clear()
            }

            // Subscribe for messages from Nano first; the commands below
            // stay queued until the descriptor write completes.
            enableNotifications(g, ch)

            val prefs = NanoPrefs(this@NanoBleService)
            enqueueCommand(timeCommand())
            enqueueCommand(prefs.settingsCommand())
            enqueueCommand(prefs.eyeStyleCommand())

            // Ask the notification listener to resend current media state.
            sendBroadcast(
                Intent(NanoNotificationListener.ACTION_MEDIA_COMMAND)
                    .setPackage(packageName)
                    .putExtra(
                        NanoNotificationListener.EXTRA_MEDIA_COMMAND,
                        NanoNotificationListener.COMMAND_SYNC
                    ),
                INTERNAL_PERMISSION
            )
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Enabling Nano notifications failed: $status")
            } else {
                Log.d(TAG, "Nano notifications enabled")
            }

            synchronized(writeQueue) {
                writing = false
            }

            pumpWrites()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            val sent: ByteArray?

            synchronized(writeQueue) {
                writing = false
                sent = writeQueue.pollFirst()
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "BLE write failed: $status")
            } else if (sent != null) {
                NanoState.lastSent = sent.toString(Charsets.UTF_8).take(60)
                NanoState.notifyChanged()
            }

            pumpWrites()
        }

        // Android 13+ delivers notifications here.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNanoMessage(ch, value)
        }

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= 33) return

            @Suppress("DEPRECATION")
            handleNanoMessage(ch, ch.value ?: return)
        }
    }

    private fun discoverServices(g: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true

        try {
            if (!g.discoverServices()) {
                Log.w(TAG, "discoverServices returned false")
                safeDisconnect(g)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Service discovery permission error", e)
            safeDisconnect(g)
        }
    }

    private fun safeDisconnect(g: BluetoothGatt) {
        try {
            g.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect failed", e)
        }
    }

    private fun enableNotifications(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic
    ) {
        val descriptor = ch.getDescriptor(CCCD_UUID)

        try {
            if (!g.setCharacteristicNotification(ch, true) || descriptor == null) {
                Log.w(TAG, "Nano notifications unavailable")
                return
            }

            synchronized(writeQueue) {
                writing = true
            }

            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, value) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value

                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }

            if (!started) {
                Log.w(TAG, "Descriptor write could not be started")

                synchronized(writeQueue) {
                    writing = false
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification subscribe permission error", e)

            synchronized(writeQueue) {
                writing = false
            }
        }
    }

    // Nano -> phone:
    //   C|NEXT, C|PREV, C|PLAYPAUSE   media controls
    //   H|WATER                       water reminder acknowledged
    //   E|<0-3>                       eye style changed by double tap
    private fun handleNanoMessage(
        ch: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (ch.uuid != CHARACTERISTIC_UUID) return

        val message = value.toString(Charsets.UTF_8).trim()
        Log.d(TAG, "Nano says: $message")

        if (message.startsWith("H|")) {
            NanoPrefs(this).recordWater()
            NanoState.notifyChanged()
            return
        }

        if (message.startsWith("E|")) {
            val style = message.substring(2).toIntOrNull() ?: return

            if (style in NanoPrefs.EYE_STYLES.indices) {
                NanoPrefs(this).eyeStyle = style
                NanoState.notifyChanged()
            }
            return
        }

        if (!message.startsWith("C|")) return

        sendBroadcast(
            Intent(NanoNotificationListener.ACTION_MEDIA_COMMAND)
                .setPackage(packageName)
                .putExtra(
                    NanoNotificationListener.EXTRA_MEDIA_COMMAND,
                    message.substring(2)
                ),
            INTERNAL_PERMISSION
        )
    }

    private fun timeCommand(): String {
        val nowMs = System.currentTimeMillis()
        val offset = java.util.TimeZone.getDefault().getOffset(nowMs) / 1000

        return "T|${nowMs / 1000L}|$offset"
    }

    private fun enqueueCommand(command: String) {
        if (command.isBlank()) return

        // Nothing is buffered while Nano is offline; time and media state
        // are resent on every connection.
        if (characteristic == null) {
            Log.d(TAG, "Nano not ready; dropping command")
            return
        }

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

                // The stack is busy; try again shortly instead of stalling.
                Log.w(TAG, "BLE write could not be started; retrying")
                mainHandler.postDelayed({ pumpWrites() }, WRITE_RETRY_MS)
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

    private fun closeGatt() {
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

    private fun scheduleRescan(delayMs: Long = 3000L) {
        if (!shouldReconnect) return

        // Always touch scan state on the main thread; GATT callbacks
        // arrive on a binder thread.
        mainHandler.post {
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
            }, delayMs)
        }
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

        val openApp = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return builder
            .setContentTitle("Nano Companion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, link: NanoState.Link) {
        statusText = text
        NanoState.update(link, text)

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

        if (timeReceiverRegistered) {
            try {
                unregisterReceiver(timeReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Time receiver unregister failed", e)
            }

            timeReceiverRegistered = false
        }

        closeGatt()

        mainHandler.removeCallbacksAndMessages(null)
        NanoState.update(NanoState.Link.OFFLINE, "Not connected")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
