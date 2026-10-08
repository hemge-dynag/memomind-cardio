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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Scans for and connects to a Bluetooth LE heart-rate sensor (e.g. Polar H10),
 * subscribes to the standard Heart Rate Measurement characteristic, and reports
 * beats per minute.
 *
 * Uses only the standard Bluetooth SIG Heart Rate service, so it works with any
 * compliant strap (Polar H10/H9, Garmin HRM, Wahoo TICKR, ...), no vendor SDK
 * required.
 *
 * Service:        0000180D-0000-1000-8000-00805f9b34fb  (Heart Rate)
 * Measurement:    00002A37-0000-1000-8000-00805f9b34fb  (notify)
 * CCCD:           00002902-0000-1000-8000-00805f9b34fb
 */
class HeartRateManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onSensorStatus(message: String)
        fun onHeartRate(bpm: Int, contactDetected: Boolean?)
    }

    private val tag = "HeartRateManager"

    private val hrServiceUuid: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val hrMeasurementUuid: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    val isConnected: Boolean
        get() = gatt != null

    @SuppressLint("MissingPermission")
    fun start() {
        val ad = adapter
        if (ad == null) {
            listener.onSensorStatus("This device has no Bluetooth adapter.")
            return
        }
        if (!ad.isEnabled) {
            listener.onSensorStatus("Bluetooth is off. Please enable it.")
            return
        }
        val scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            listener.onSensorStatus("BLE scanner unavailable.")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(hrServiceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        listener.onSensorStatus("Scanning for a heart-rate sensor…")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            scanning = false
            adapter?.bluetoothLeScanner?.stopScan(this)
            val name = result.device.name ?: result.scanRecord?.deviceName ?: result.device.address
            listener.onSensorStatus("Found $name. Connecting…")
            gatt = result.device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onSensorStatus("Scan failed (code $errorCode).")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onSensorStatus("Sensor connected. Discovering services…")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onSensorStatus("Sensor disconnected.")
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(hrServiceUuid)?.getCharacteristic(hrMeasurementUuid)
            if (ch == null) {
                listener.onSensorStatus("Heart Rate service not found on this device.")
                return
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(cccdUuid)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } else {
                listener.onSensorStatus("Notifications enabled (no CCCD).")
            }
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
        if (value == null || value.isEmpty()) return
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
        if (scanning) {
            scanning = false
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
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
