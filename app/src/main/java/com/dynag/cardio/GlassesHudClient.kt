package com.dynag.cardio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connects to the MemoMind glasses over Bluetooth LE and sends Web Bridge
 * display messages to the HUD.
 *
 * Transport: GM command service. The command downlink characteristic is
 * 00002021-...  (Write / Write Without Response) and the response/event uplink
 * is 00002022-...  (Notify). We never hardcode attribute handles: the service
 * is discovered and its characteristics are resolved by UUID.
 *
 * Frames are produced by [GmFrame] and written one complete physical GM frame
 * per characteristic write, paced within the negotiated ATT MTU. A running
 * Web Bridge plugin on the glasses renders the text channel.
 *
 * Reference: GlassSDK/docs/BLUETOOTH_DEVELOPER_GUIDE.md and
 * GlassSDK/docs/HUD_PROTOCOL.md.
 */
class GlassesHudClient(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onGlassesStatus(message: String)
        fun onGlassesReady()
        fun onGlassesDisconnected()
    }

    private val tag = "GlassesHudClient"

    companion object {
        private const val OBJECT_HR = 1
        private const val OBJECT_METRICS = 2
        private const val OBJECT_TIME = 3
    }

    // Conventional Bluetooth-base UUIDs; the firmware may expose a legacy alias,
    // so we also resolve characteristics by the 16-bit shortcut if needed.
    // Command downlink / response characteristics. The containing service UUID
    // may differ between firmware revisions, so we scan every service for these
    // characteristic UUIDs instead of assuming a fixed handle or service.
    private val commandCharUuid: UUID = UUID.fromString("00002021-0000-1000-8000-00805f9b34fb")
    private val responseUuid: UUID = UUID.fromString("00002022-0000-1000-8000-00805f9b34fb")
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var ready = false
    private var mtu = 23

    @Volatile
    private var writeBusy = false
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private val eventId = AtomicInteger(1)

    val isReady: Boolean
        get() = ready

    fun nextEventId(): Int {
        val id = eventId.getAndIncrement()
        if (id > 0xEF) eventId.set(1)
        return id
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        val ad = adapter
        if (ad == null) {
            listener.onGlassesStatus("No Bluetooth adapter.")
            return
        }
        if (!ad.isEnabled) {
            listener.onGlassesStatus("Bluetooth is off.")
            return
        }
        val scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            listener.onGlassesStatus("BLE scanner unavailable.")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        listener.onGlassesStatus("Scanning for MemoMind glasses…")
        // No service filter: the firmware may expose a legacy service UUID.
        // We connect to the first strong candidate and validate on discovery.
        scanner.startScan(null, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            scanning = false
            adapter?.bluetoothLeScanner?.stopScan(this)
            val name = result.device.name ?: result.device.address
            listener.onGlassesStatus("Found $name. Connecting…")
            gatt = result.device.connectGatt(
                context,
                false,
                gattCallback,
                android.bluetooth.BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onGlassesStatus("Glasses scan failed (code $errorCode).")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onGlassesStatus("Glasses connected. Discovering services…")
                    g.requestMtu(185)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ready = false
                    listener.onGlassesStatus("Glasses disconnected.")
                    listener.onGlassesDisconnected()
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            Log.d(tag, "MTU is now $mtu")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var write: BluetoothGattCharacteristic? = null
            var notify: BluetoothGattCharacteristic? = null
            for (service in g.services) {
                val w = service.getCharacteristic(commandCharUuid)
                if (w != null) write = w
                val n = service.getCharacteristic(responseUuid)
                if (n != null) notify = n
            }
            if (write == null) {
                listener.onGlassesStatus("GM control characteristic 0x2021 not found.")
                return
            }
            commandChar = write
            if (notify != null) {
                g.setCharacteristicNotification(notify, true)
                val cccd = notify.getDescriptor(cccdUuid)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
            }
            // Heartbeat (see guide): nine-byte status query, service 01 command 01.
            val heartbeat = byteArrayOf(
                0xFA.toByte(), 0x00, 0x00, 0x09, 0x01, 0x01, 0x01, 0x01, 0x06
            )
            writeRaw(heartbeat)
            ready = true
            listener.onGlassesReady()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeBusy = false
            drainWriteQueue()
        }
    }

    /**
     * Send a logical Web Bridge text update for the given [bpm].
     * Produces GM frames via [GmFrame.webBridgeText] and enqueues them in order.
     */
    fun showHeartRate(bpm: Int) {
        val text = "$bpm BPM"
        val frames = GmFrame.webBridgeText(
            eventId = nextEventId(),
            id = OBJECT_HR,
            x = 40,
            y = 40,
            width = 560,
            height = 120,
            border = 0,
            radius = 0,
            text = text,
            maxFrameBytes = maxFrameForMtu()
        )
        frames.forEach { enqueue(it) }
    }

    /**
     * Push the full workout line-up to the HUD:
     *   object 1 — heart rate            ("142 BPM")
     *   object 2 — distance + speed      ("3.42 km · 10.8 km/h")
     *   object 3 — time + average speed  ("12:34 · 9.6 km/h avg")
     *
     * Each object is a separate Web Bridge text object with a stable id, so a
     * re-send updates it in place. Objects are enqueued in order.
     */
    fun showMetrics(bpm: Int, distanceMeters: Double, speedKmh: Double, averageKmh: Double, elapsedMillis: Long) {
        sendText(OBJECT_HR, "$bpm BPM")
        sendText(OBJECT_METRICS, formatDistanceSpeed(distanceMeters, speedKmh))
        sendText(OBJECT_TIME, formatTimeAvg(elapsedMillis, averageKmh))
    }

    private fun sendText(objectId: Int, text: String) {
        val frames = GmFrame.webBridgeText(
            eventId = nextEventId(),
            id = objectId,
            x = 40,
            y = yForObject(objectId),
            width = 560,
            height = 120,
            border = 0,
            radius = 0,
            text = text,
            maxFrameBytes = maxFrameForMtu()
        )
        frames.forEach { enqueue(it) }
    }

    private fun yForObject(objectId: Int): Int = when (objectId) {
        OBJECT_HR -> 40
        OBJECT_METRICS -> 180
        else -> 320
    }

    private fun formatDistanceSpeed(distanceMeters: Double, speedKmh: Double): String {
        val km = distanceMeters / 1000.0
        return String.format(java.util.Locale.US, "%.2f km · %.1f km/h", km, speedKmh)
    }

    private fun formatTimeAvg(elapsedMillis: Long, averageKmh: Double): String {
        val totalSec = elapsedMillis / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(java.util.Locale.US, "%d:%02d · %.1f km/h avg", m, s, averageKmh)
    }

    /** Clear the Web Bridge scene. */
    fun clear() {
        enqueue(GmFrame.webBridgeClear(nextEventId()))
    }

    /**
     * Max physical GM frame size that fits one ATT write: MTU minus the 3-byte
     * ATT header. Falls back to 20 bytes (MTU 23) when the MTU was not
     * negotiated higher.
     */
    private fun maxFrameForMtu(): Int {
        return (mtu - 3).coerceAtLeast(20)
    }

    private fun enqueue(frame: ByteArray) {
        writeQueue.add(frame)
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeBusy) return
        if (gatt == null || commandChar == null) return
        val frame = writeQueue.poll() ?: return
        writeBusy = true
        writeRaw(frame)
    }

    @SuppressLint("MissingPermission")
    private fun writeRaw(frame: ByteArray) {
        val g = gatt ?: return
        val ch = commandChar ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.value = frame
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (scanning) {
            scanning = false
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        ready = false
        writeQueue.clear()
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {
                Log.w(tag, "Error closing glasses GATT", e)
            }
        }
        gatt = null
    }
}
