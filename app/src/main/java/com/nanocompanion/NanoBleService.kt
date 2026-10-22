package com.nanocompanion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class NanoBleService : Service() {

    companion object {
        private const val TAG = "NanoBleService"
        private const val DEVICE_NAME = "Nano"

        const val ACTION_CONNECT =
            "com.nanocompanion.CONNECT"
        const val ACTION_DISCONNECT =
            "com.nanocompanion.DISCONNECT"
        const val ACTION_SEND_COMMAND =
            "com.nanocompanion.SEND_COMMAND"
        const val ACTION_FORWARD_NOTIFICATION =
            "com.nanocompanion.FORWARD_NOTIFICATION"
        const val ACTION_CONNECTION_STATE =
            "com.nanocompanion.CONNECTION_STATE"
        const val ACTION_DEVICE_MESSAGE =
            "com.nanocompanion.DEVICE_MESSAGE"

        const val EXTRA_COMMAND = "command"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_MESSAGE = "message"

        private val SERVICE_UUID = UUID.fromString(
            "7a1c0001-5e0d-4b8e-9f3a-2d6c1b4a0001"
        )

        private val CHARACTERISTIC_UUID = UUID.fromString(
            "7a1c0002-5e0d-4b8e-9f3a-2d6c1b4a0001"
        )

        private val CCCD_UUID = UUID.fromString(
            "00002902-0000-1000-8000-00805f9b34fb"
        )
    }

    private val writeQueue = ConcurrentLinkedQueue<String>()

    private var bluetoothGatt: BluetoothGatt? = null
    private var nanoCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var connected = false

    @Volatile
    private var scanning = false

    private var receiverRegistered = false
    private var writeInProgress = false
    private var notificationsEnabled = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CONNECT -> connectToNano()

                ACTION_DISCONNECT -> disconnectFromNano()

                ACTION_SEND_COMMAND,
                ACTION_FORWARD_NOTIFICATION -> {
                    intent.getStringExtra(EXTRA_COMMAND)?.let {
                        enqueueCommand(it)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECT)
            addAction(ACTION_DISCONNECT)
            addAction(ACTION_SEND_COMMAND)
            addAction(ACTION_FORWARD_NOTIFICATION)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                commandReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(commandReceiver, filter)
        }

        receiverRegistered = true
        Log.i(TAG, "Nano BLE service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connectToNano()

            ACTION_DISCONNECT -> disconnectFromNano()

            ACTION_SEND_COMMAND,
            ACTION_FORWARD_NOTIFICATION -> {
                intent.getStringExtra(EXTRA_COMMAND)?.let {
                    enqueueCommand(it)
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        val manager = getSystemService(BLUETOOTH_SERVICE)
            as BluetoothManager
        return manager.adapter
    }

    @SuppressLint("MissingPermission")
    private fun connectToNano() {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "Bluetooth permissions not granted")
            broadcastConnectionState(false)
            return
        }

        val adapter = getBluetoothAdapter()

        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth unavailable or disabled")
            broadcastConnectionState(false)
            return
        }

        if (connected) {
            Log.i(TAG, "Already connected to Nano")
            return
        }

        if (scanning) return

        val scanner = adapter.bluetoothLeScanner

        if (scanner == null) {
            Log.e(TAG, "BLE scanner unavailable")
            broadcastConnectionState(false)
            return
        }

        scanning = true
        Log.i(TAG, "Scanning for $DEVICE_NAME")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            scanning = false
            Log.e(TAG, "Unable to start BLE scan", e)
            broadcastConnectionState(false)
        }
    }

    private val scanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            if (!hasBluetoothPermission()) return

            val device = result.device

            val name = result.scanRecord?.deviceName
                ?: try {
                    device.name
                } catch (_: Exception) {
                    null
                }

            if (name == DEVICE_NAME) {
                Log.i(TAG, "Found Nano: ${device.address}")
                stopScan()
                connectGatt(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.e(TAG, "BLE scan failed: $errorCode")
            broadcastConnectionState(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try {
            if (hasBluetoothPermission()) {
                getBluetoothAdapter()
                    ?.bluetoothLeScanner
                    ?.stopScan(scanCallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stop scan", e)
        } finally {
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) return

        try {
            bluetoothGatt?.close()
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(
                    this,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                device.connectGatt(this, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to connect to Nano", e)
            broadcastConnectionState(false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT connection error: $status")
                connected = false
                nanoCharacteristic = null
                writeInProgress = false
                notificationsEnabled = false
                broadcastConnectionState(false)

                try {
                    gatt.close()
                } catch (_: Exception) {
                }

                if (bluetoothGatt === gatt) {
                    bluetoothGatt = null
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    bluetoothGatt = gatt
                    writeInProgress = false
                    notificationsEnabled = false

                    Log.i(TAG, "Connected to Nano")
                    broadcastConnectionState(true)

                    if (hasBluetoothPermission()) {
                        gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    nanoCharacteristic = null
                    writeInProgress = false
                    notificationsEnabled = false

                    writeQueue.clear()
                    broadcastConnectionState(false)

                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }

                    if (bluetoothGatt === gatt) {
                        bluetoothGatt = null
                    }

                    Log.i(TAG, "Disconnected from Nano")
                }
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            val characteristic =
                service?.getCharacteristic(CHARACTERISTIC_UUID)

            if (characteristic == null) {
                Log.e(TAG, "Nano characteristic not found")
                return
            }

            nanoCharacteristic = characteristic

            val canNotify =
                characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

            if (canNotify) {
                enableNotifications(gatt, characteristic)
            } else {
                notificationsEnabled = true
                Log.w(TAG, "Characteristic does not support notifications")
                pumpWrites()
            }

            Log.i(TAG, "Nano characteristic ready")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != CHARACTERISTIC_UUID) return

            val message = characteristic.value
                ?.toString(Charsets.UTF_8)
                ?.trim()
                .orEmpty()

            if (message.isNotBlank()) {
                handleDeviceMessage(message)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != CHARACTERISTIC_UUID) return

            val message = value.toString(Charsets.UTF_8).trim()

            if (message.isNotBlank()) {
                handleDeviceMessage(message)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            notificationsEnabled =
                status == BluetoothGatt.GATT_SUCCESS

            if (notificationsEnabled) {
                Log.i(TAG, "BLE notifications enabled")
            } else {
                Log.e(TAG, "Enabling BLE notifications failed: $status")
            }

            pumpWrites()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeInProgress = false

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "BLE write failed: $status")
            }

            pumpWrites()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (!hasBluetoothPermission()) return

        try {
            val enabled = gatt.setCharacteristicNotification(
                characteristic,
                true
            )

            if (!enabled) {
                Log.e(TAG, "Unable to enable characteristic notifications")
                return
            }

            val descriptor = characteristic.getDescriptor(CCCD_UUID)

            if (descriptor == null) {
                Log.e(TAG, "CCCD descriptor 2902 not found")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )

                if (result != BluetoothStatusCodes.SUCCESS) {
                    Log.e(TAG, "Descriptor write failed: $result")
                }
            } else {
                @Suppress("DEPRECATION")
                descriptor.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                @Suppress("DEPRECATION")
                val accepted = gatt.writeDescriptor(descriptor)

                if (!accepted) {
                    Log.e(TAG, "Descriptor write was rejected")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to configure notifications", e)
        }
    }

    private fun handleDeviceMessage(message: String) {
        Log.i(TAG, "Received from Nano: $message")

        if (message.startsWith("C|")) {
            val command = message.substringAfter("C|").trim()

            val intent = Intent(
                NanoNotificationListener.ACTION_MEDIA_COMMAND
            )
                .setPackage(packageName)
                .putExtra(
                    NanoNotificationListener.EXTRA_MEDIA_COMMAND,
                    command
                )

            sendBroadcast(intent)
        }

        sendBroadcast(
            Intent(ACTION_DEVICE_MESSAGE)
                .setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private fun enqueueCommand(command: String) {
        if (command.isBlank()) return

        writeQueue.offer(command)
        pumpWrites()
    }

    @SuppressLint("MissingPermission")
    private fun pumpWrites() {
        if (!connected || !hasBluetoothPermission()) return
        if (writeInProgress) return

        val gatt = bluetoothGatt ?: return
        val characteristic = nanoCharacteristic ?: return

        // Wait until notification subscription setup has completed.
        if (!notificationsEnabled) return

        val command = writeQueue.peek() ?: return
        val properties = characteristic.properties

        val writeType = when {
            properties and
                BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            properties and
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            else -> {
                Log.e(TAG, "Characteristic is not writable")
                writeQueue.poll()
                return
            }
        }

        try {
            val bytes = command.toByteArray(Charsets.UTF_8)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                writeInProgress = true

                val result = gatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    writeType
                )

                if (result == BluetoothStatusCodes.SUCCESS) {
                    writeQueue.poll()
                } else {
                    writeInProgress = false
                    Log.e(TAG, "BLE write rejected: $result")
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType

                @Suppress("DEPRECATION")
                characteristic.value = bytes

                writeInProgress = true

                @Suppress("DEPRECATION")
                val accepted = gatt.writeCharacteristic(characteristic)

                if (accepted) {
                    writeQueue.poll()
                } else {
                    writeInProgress = false
                    Log.e(TAG, "BLE write was rejected")
                }
            }
        } catch (e: Exception) {
            writeInProgress = false
            Log.e(TAG, "Error writing to Nano", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectFromNano() {
        stopScan()

        connected = false
        nanoCharacteristic = null
        writeInProgress = false
        notificationsEnabled = false
        writeQueue.clear()

        val gatt = bluetoothGatt
        bluetoothGatt = null

        try {
            gatt?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "GATT disconnect failed", e)
        }

        try {
            gatt?.close()
        } catch (_: Exception) {
        }

        broadcastConnectionState(false)
    }

    private fun broadcastConnectionState(isConnected: Boolean) {
        sendBroadcast(
            Intent(ACTION_CONNECTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_CONNECTED, isConnected)
        )
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(commandReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }

        disconnectFromNano()
        super.onDestroy()

        Log.i(TAG, "Nano BLE service destroyed")
    }
}
