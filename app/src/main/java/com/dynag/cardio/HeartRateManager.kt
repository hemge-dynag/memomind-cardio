package com.dynag.cardio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/** A discovered Bluetooth LE peripheral offered to the user as a heart-rate source. */
data class BleSensor(
    val address: String,
    val name: String,
    val rssi: Int,
    val hrServiceAdvertised: Boolean
) {
    val displayName: String
        get() {
            val base = if (name.isBlank()) address else name
            val tag = if (hrServiceAdvertised) "Heart rate" else "Other"
            return "$base  ·  $tag  ·  $rssi dBm"
        }
}

/**
 * Scans for, connects to, and subscribes to a Bluetooth LE heart-rate source.
 *
 * Any device that exposes the standard Bluetooth SIG **Heart Rate service**
 * (`0x180D`) works, without a vendor SDK. That covers chest straps (Polar H10,
 * Garmin HRM, Wahoo TICKR…) and wearables that broadcast their optical heart
 * rate over the standard Bluetooth Heart Rate Profile, including:
 *
 *  - **Fitbit Charge 6**      (swipe down → "HR on Equipment")
 *  - **Google Fitbit Air**    (Google Health app → Connections → Share heart rate)
 *  - **Google Pixel Watch 2+** (Connected Fitness → Connect)
 *
 * Those devices only broadcast while their "share heart rate" mode is active,
 * and they accept a limited number of simultaneous connections.
 *
 * Service:      0000180D-…  (Heart Rate)
 * Measurement:  00002A37-…  (notify)
 * CCCD:         00002902-…
 */
class HeartRateManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onSensorStatus(message: String)
        fun onHeartRate(bpm: Int, contactDetected: Boolean?)
        /** Scan finished: the candidate devices, best first. Empty if none found. */
        fun onSensorsDiscovered(sensors: List<BleSensor>)
        /** Connected, service validated, notifications subscribed. */
        fun onSensorReady()
        fun onSensorDisconnected()
        fun onSensorError(message: String)
    }

    private val tag = "HeartRateManager"

    private val hrServiceUuid: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val hrMeasurementUuid: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val handler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var restartPending = false
    private var connected = false
    private var notificationsPending = false
    /** Pending auto-stop runnable, kept so a restart can cancel it. */
    private var stopTimer: Runnable? = null

    private val found = LinkedHashMap<String, BleSensor>()
    /** Device handles captured during the scan, reused by [connect]. */
    private val devices = LinkedHashMap<String, BluetoothDevice>()

    val isConnected: Boolean
        get() = connected

    /**
     * Scan for [scanMillis], then report the discovered devices through
     * [Listener.onSensorsDiscovered]. The scan is unfiltered on purpose: some
     * wearables advertise the Heart Rate service only during the connection, so
     * we list everything and let the user pick, validating the service after
     * connecting.
     */
    @SuppressLint("MissingPermission")
    fun scanForSensors(scanMillis: Long = 10000) {
        val ad = adapter
        if (ad == null) {
            listener.onSensorError("This device has no Bluetooth adapter.")
            return
        }
        if (!ad.isEnabled) {
            listener.onSensorError("Bluetooth is off. Please enable it.")
            return
        }
        val scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            listener.onSensorError("BLE scanner unavailable.")
            return
        }
        // Calling startScan while a scan is already running makes the platform
        // answer onScanFailed(SCAN_FAILED_ALREADY_STARTED = 1). Tear the current
        // scan down first and restart once the stack has actually released it.
        if (scanning) {
            abortScan()
            if (restartPending) return
            restartPending = true
            handler.postDelayed({
                restartPending = false
                scanForSensors(scanMillis)
            }, 600)
            return
        }
        found.clear()
        devices.clear()
        scanning = true
        listener.onSensorStatus(
            "Scanning… On a Fitbit/Pixel Watch, first start \"share heart rate\"."
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            listener.onSensorError("Bluetooth permission missing for scan.")
            return
        } catch (e: IllegalStateException) {
            scanning = false
            listener.onSensorError("Could not start scan: ${e.message ?: "already running"}.")
            return
        }
        val stop = Runnable { finishScan() }
        stopTimer?.let { handler.removeCallbacks(it) }
        stopTimer = stop
        handler.postDelayed(stop, scanMillis)
    }

    /** Stop the scan after its duration and hand the results to the listener. */
    @SuppressLint("MissingPermission")
    private fun finishScan() {
        if (!scanning) return
        scanning = false
        stopTimer = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        val sensors = found.values.sortedWith(
            compareByDescending<BleSensor> { it.hrServiceAdvertised }
                .thenByDescending { it.rssi }
        )
        listener.onSensorStatus("Scan finished: ${sensors.size} device(s).")
        listener.onSensorsDiscovered(sensors)
    }

    /** Cancel any in-flight scan without delivering results. */
    @SuppressLint("MissingPermission")
    private fun abortScan() {
        scanning = false
        stopTimer?.let { handler.removeCallbacks(it) }
        stopTimer = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val record = result.scanRecord
            val advertisedHr = record?.serviceUuids?.any { it.uuid == hrServiceUuid } ?: false
            val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: ""
            val address = result.device.address
            val prev = found[address]
            if (prev == null || result.rssi > prev.rssi) {
                found[address] = BleSensor(address, name, result.rssi, advertisedHr)
                devices[address] = result.device
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            stopTimer?.let { handler.removeCallbacks(it) }
            stopTimer = null
            val reason = when (errorCode) {
                1 -> "already started"
                2 -> "registration failed"
                3 -> "internal error"
                4 -> "out of resources"
                5 -> "too many scans"
                else -> "code $errorCode"
            }
            listener.onSensorError("Scan failed ($reason). Tap Scan again.")
        }
    }

    /** Connect to a device chosen from [scanForSensors]' results. */
    @SuppressLint("MissingPermission")
    fun connect(sensor: BleSensor) {
        val ad = adapter ?: run {
            listener.onSensorError("No Bluetooth adapter.")
            return
        }
        if (scanning) {
            scanning = false
            ad.bluetoothLeScanner?.stopScan(scanCallback)
        }
        listener.onSensorStatus("Connecting to ${sensor.name.ifBlank { sensor.address }}…")
        // Prefer the handle kept from the scan: `getRemoteDevice` gave the old
        // manager an address string that connected fine, but reusing the scanned
        // object also keeps the address type (public/random) the peripheral
        // actually advertises.
        val device = devices[sensor.address] ?: ad.getRemoteDevice(sensor.address)
        gatt?.let { runCatching { it.close() } }
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // connectGatt returns a fresh handle on some devices (private
            // addressing / autoConnect retries), so the callback's object is
            // the only reliable reference afterwards.
            if (gatt !== g) gatt = g
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        listener.onSensorError("Connection failed (status $status).")
                        runCatching { g.disconnect() }
                        return
                    }
                    listener.onSensorStatus("Connected. Discovering services…")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connected) listener.onSensorDisconnected()
                    connected = false
                    controlCharacteristic = null
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(hrServiceUuid)?.getCharacteristic(hrMeasurementUuid)
            if (ch == null) {
                listener.onSensorError("No Heart Rate service on this device.")
                connected = false
                g.disconnect()
                return
            }
            controlCharacteristic = ch
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(cccdUuid)
            if (cccd == null) {
                // Without a CCCD the notification cannot be enabled: report the
                // sensor as ready anyway, the strap is connected.
                connected = true
                listener.onSensorReady()
                return
            }
            // "Ready" is only true once the descriptor write is acknowledged:
            // announcing it here made the glasses show a zone with no readings,
            // because notifications were still off when the first push fired.
            notificationsPending = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!notificationsPending) return
            notificationsPending = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onSensorError("Could not enable heart-rate notifications (status $status).")
                return
            }
            connected = true
            listener.onSensorReady()
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleMeasurement(characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleMeasurement(value)
        }
    }

    /**
     * Parse the Heart Rate Measurement characteristic (0x2A37).
     *
     * Byte 0 is the flags field:
     *   bit 0    : 0 = uint8 BPM, 1 = uint16 BPM
     *   bits 1-2 : sensor contact status
     *   bit 3    : energy expended present
     *   bit 4    : RR interval present
     */
    private fun handleMeasurement(value: ByteArray?) {
        if (value == null || value.size < 2) return
        val flags = value[0].toInt() and 0xFF
        val isU16 = (flags and 0x01) != 0
        val bpm: Int
        if (isU16) {
            if (value.size < 3) return
            bpm = (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else {
            bpm = value[1].toInt() and 0xFF
        }
        val contact: Boolean? = when ((flags shr 1) and 0x03) {
            0b10 -> true
            0b11 -> false
            else -> null
        }
        listener.onHeartRate(bpm, contact)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        abortScan()
        restartPending = false
        connected = false
        controlCharacteristic = null
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {
                Log.w(tag, "Error closing GATT", e)
            }
        }
        gatt = null
    }
}
