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
 * Connects to the MemoMind glasses over Bluetooth LE and sends display data to
 * the HUD, through two independent channels:
 *
 *  - the Web Bridge text channel (service 0x0F / command 0x28 / channel 2),
 *    rendered by whatever Web Bridge plugin is running;
 *  - the paired **cardio plugin** protocol (same envelope, channel 0x4344),
 *    which draws the big BPM, zone and 30 s trend itself. The plugin signals
 *    readiness with an `EVENT_READY` byte on channel 0x4355.
 *
 * Transport: GM command service. The command downlink characteristic is
 * 00002021-...  (Write / Write Without Response) and the response/event uplink
 * is 00002022-...  (Notify). We never hardcode attribute handles: the service
 * is discovered and its characteristics are resolved by UUID.
 *
 * Frames are produced by [GmFrame] and written one complete physical GM frame
 * per characteristic write, paced within the negotiated ATT MTU.
 *
 * Reference: GlassSDK/docs/PROTOCOL.md and GlassSDK/docs/BLUETOOTH_DEVELOPER_GUIDE.md.
 */
class GlassesHudClient(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onGlassesStatus(message: String)
        fun onGlassesReady()
        fun onGlassesDisconnected()
        /** The paired cardio plugin on the glasses announced EVENT_READY. */
        fun onCardioReady()

        /**
         * No glasses advertised the GM service. [devices] is a list of
         * (address, name) seen during the scan, for the user to pick the
         * glasses once; the choice is remembered for next time.
         */
        fun onGlassesChooser(devices: List<Pair<String, String>>)
    }

    private val tag = "GlassesHudClient"

    companion object {
        private const val OBJECT_HR = 1
        private const val OBJECT_METRICS = 2
        private const val OBJECT_TIME = 3

        // cardio plugin status (see protocol.js STATE_*)
        private const val STATE_IDLE = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_READING = 2
        private const val STATE_LOST = 3
        private const val STATE_ERROR = 4

        // Minimum spacing between two BPM payloads towards the glasses.
        private const val CARDIO_PUSH_INTERVAL_MS = 250L
        // Give up when nothing answers a link attempt, and try the next device.
        private const val SCAN_TIMEOUT_MS = 12000L
        // Legacy alias the firmware may advertise instead of the Bluetooth-base
        // service (see BLUETOOTH_DEVELOPER_GUIDE.md, `BlueConfigDefault`).
        private const val LEGACY_GM_SERVICE = "fb349b5f-8000-0080-0010-002020000000"
        // Bluetooth-base GM service; its characteristics are 0x2021/0x2022, but
        // advertisements list the *service* UUID, so match 0x2020 here.
        private const val GM_SERVICE_16 = "00002020"
        // Persisted address of the glasses the user picked once.
        private const val PREFS = "memo_cardio"
        private const val PREF_GLASSES_ADDR = "glasses_addr"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Set once the running glasses plugin has announced EVENT_READY. */
    @Volatile
    var cardioReady: Boolean = false
        private set

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var cardioSentAt = 0L
    private var lastCardioBpm = 0
    private var cardioPending: ByteArray? = null

    private val cardioFlush = Runnable {
        val payload = cardioPending ?: return@Runnable
        cardioPending = null
        sendCardioPayload(payload)
    }

    // Conventional Bluetooth-base UUIDs; the firmware may expose a legacy alias,
    // so we also resolve characteristics by the 16-bit shortcut if needed.
    // Command downlink / response characteristics. The containing service UUID
    // may differ between firmware revisions, so we scan every service for these
    // characteristic UUIDs instead of assuming a fixed handle or service.
    private val commandCharUuid: UUID = UUID.fromString("00002021-0000-1000-8000-00805f9b34fb")
    private val responseUuid: UUID = UUID.fromString("00002022-0000-1000-8000-00805f9b34fb")
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    /** UUIDs as printed by the firmware, for the diagnostic log. */
    private fun uuidText(u: UUID): String = u.toString().lowercase()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var ready = false
    private var mtu = 23
    private var linking = false
    private var linkCandidate = -1
    private val candidates = ArrayList<Pair<android.bluetooth.BluetoothDevice, String>>()
    /** Devices that advertise the GM service; always tried before [candidates]. */
    private val gmCandidates = ArrayList<Pair<android.bluetooth.BluetoothDevice, String>>()
    private val advertisedServices = ArrayList<String>()

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
        // A re-connect must never stack on a live link: the old GATT would keep
        // its notifications and its queued writes alive next to the new one.
        val wasScanning = scanning
        if (gatt != null || scanning) disconnect()

        // If the user already picked the glasses once, go straight to them.
        val saved = prefs.getString(PREF_GLASSES_ADDR, null)
        if (!saved.isNullOrBlank()) {
            listener.onGlassesStatus("Connecting to the saved glasses ($saved)…")
            connectToAddress(saved)
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // If a scan was just torn down, let the stack release it before starting
        // a new one, otherwise startScan answers SCAN_FAILED_ALREADY_STARTED.
        val start = Runnable {
            scanning = true
            listener.onGlassesStatus("Scanning for MemoMind glasses…")
            // Devices advertising the GM service are queued first; anything
            // else is only tried as a fallback, so a stray peripheral in range
            // cannot be picked instead of the glasses, yet glasses that omit
            // the service UUID from their advertisement are still reachable.
            candidates.clear()
            gmCandidates.clear()
            try {
                scanner.startScan(null, settings, scanCallback)
            } catch (e: SecurityException) {
                scanning = false
                listener.onGlassesStatus("Bluetooth permission missing for scan.")
                return@Runnable
            } catch (e: IllegalStateException) {
                scanning = false
                listener.onGlassesStatus("Glasses scan busy, retry.")
                return@Runnable
            }
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        }
        if (wasScanning) {
            handler.postDelayed(start, 600)
        } else {
            start.run()
        }
    }

    private val scanTimeout = Runnable {
        if (!scanning) return@Runnable
        // No GM-advertising device showed up: instead of linking to a random
        // peripheral, ask the user to pick the glasses from what was seen.
        val list = orderedCandidates()
        if (list.isEmpty()) {
            stopScan()
            listener.onGlassesStatus("No MemoMind glasses found. Bring them close and retry.")
            return@Runnable
        }
        stopScan()
        linking = false
        listener.onGlassesStatus("Glasses not auto-detected. Pick them in the list.")
        listener.onGlassesChooser(list.map { it.first.address to it.second })
    }

    /**
     * Connect directly to a known device address (a remembered or user-picked
     * glasses), skipping the scan. The address is persisted on success.
     */
    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String) {
        val ad = adapter ?: run {
            listener.onGlassesStatus("No Bluetooth adapter.")
            return
        }
        if (gatt != null || scanning) disconnect()
        val device = runCatching { ad.getRemoteDevice(address) }.getOrNull() ?: run {
            listener.onGlassesStatus("Saved glasses $address unavailable; scanning instead.")
            prefs.edit().remove(PREF_GLASSES_ADDR).apply()
            connect()
            return
        }
        linking = true
        linkCandidate = 0
        val name = runCatching { device.name }.getOrNull() ?: address
        listener.onGlassesStatus("Connecting to $name…")
        gatt?.let { runCatching { it.close() } }
        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            android.bluetooth.BluetoothDevice.TRANSPORT_LE
        )
    }

    /**
     * True when the advertisement carries the GM control service, either under
     * its Bluetooth-base UUID or under the legacy alias the firmware may use.
     * Any other peripheral in range is ignored instead of being linked to.
     */
    private fun advertisesGmService(result: ScanResult): Boolean {
        val uuids = result.scanRecord?.serviceUuids
        if (uuids.isNullOrEmpty()) return false
        for (parcel in uuids) {
            val u = parcel.uuid ?: continue
            val text = uuidText(u)
            if (text.startsWith(GM_SERVICE_16)) return true
            if (text == LEGACY_GM_SERVICE) return true
        }
        return false
    }

    /** All candidates, GM-advertising ones first. */
    private fun orderedCandidates(): List<Pair<android.bluetooth.BluetoothDevice, String>> =
        gmCandidates + candidates

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val device = result.device
            val gm = advertisesGmService(result)
            val seen = gmCandidates.any { it.first.address == device.address } ||
                candidates.any { it.first.address == device.address }
            if (!seen) {
                val name = runCatching { device.name }.getOrNull() ?: device.address
                if (gm) {
                    gmCandidates.add(device to name)
                    listener.onGlassesStatus("Maybe glasses: $name (advertises GM)")
                } else {
                    candidates.add(device to name)
                    Log.d(tag, "candidate ${device.address} (no GM service, fallback)")
                }
            }
            if (linking) return
            // Only commit to a link attempt once a GM-advertising device shows
            // up; otherwise keep scanning so a stray peripheral is not chosen.
            if (!gm && gmCandidates.isEmpty()) return
            stopScan()
            tryCandidate(0)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onGlassesStatus("Glasses scan failed (code $errorCode).")
        }
    }

    /** Stop the scan but leave the link attempt alone. */
    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    /**
     * Connect to candidate [index] and remember the others: a device that was
     * in range but is not the glasses (or that refuses the link) must not stop
     * the search, which is what happened when only the first hit was tried.
     */
    @SuppressLint("MissingPermission")
    private fun tryCandidate(index: Int) {
        val list = orderedCandidates()
        if (index >= list.size) {
            linking = false
            // A stale saved address that no longer answers must be forgotten so
            // the next Connect scans afresh and offers the picker again.
            if (prefs.contains(PREF_GLASSES_ADDR) && candidates.isEmpty() && gmCandidates.isEmpty()) {
                prefs.edit().remove(PREF_GLASSES_ADDR).apply()
                listener.onGlassesStatus("Saved glasses did not answer; they have been forgotten. Tap Connect to scan again.")
                return
            }
            listener.onGlassesStatus("No MemoMind glasses answered. Retry with them awake.")
            return
        }
        linking = true
        linkCandidate = index
        val (device, name) = list[index]
        listener.onGlassesStatus("Found $name. Connecting…")
        gatt?.let { runCatching { it.close() } }
        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            android.bluetooth.BluetoothDevice.TRANSPORT_LE
        )
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
                    cardioReady = false
                    cardioPending = null
                    handler.removeCallbacks(cardioFlush)
                    g.close()
                    if (gatt === g) gatt = null
                    if (linking) {
                        // The device refused or dropped before discovery: the
                        // next scanned candidate may be the real glasses.
                        linking = false
                        tryCandidate(linkCandidate + 1)
                        return
                    }
                    listener.onGlassesStatus("Glasses disconnected.")
                    if (advertisedServices.isNotEmpty()) {
                        listener.onGlassesStatus("Services: ${advertisedServices.joinToString(", ")}")
                    }
                    listener.onGlassesDisconnected()
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
            advertisedServices.clear()
            for (service in g.services) advertisedServices.add(uuidText(service.uuid))
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onGlassesStatus("Service discovery failed (status $status).")
            }
            for (service in g.services) {
                Log.d(tag, "service ${uuidText(service.uuid)}")
                for (c in service.characteristics) {
                    Log.d(tag, "  char ${uuidText(c.uuid)} props=${c.properties}")
                }
                val w = service.getCharacteristic(commandCharUuid)
                if (w != null) write = w
                val n = service.getCharacteristic(responseUuid)
                if (n != null) notify = n
            }
            if (write == null) {
                // Not the glasses (or an old firmware): report what the device
                // actually exposes, then try the next scanned candidate.
                val summary = g.services.joinToString(", ") { uuidText(it.uuid) }
                Log.w(tag, "0x2021 missing; services=[$summary]")
                listener.onGlassesStatus("GM control characteristic 0x2021 not found.")
                if (summary.isNotBlank()) listener.onGlassesStatus("Services: $summary")
                linking = false
                runCatching { g.disconnect() }
                if (gatt === g) gatt = null
                tryCandidate(linkCandidate + 1)
                return
            }
            if (notify == null) {
                listener.onGlassesStatus("GM uplink characteristic 0x2022 not found.")
            }
            linking = false
            candidates.clear()
            gmCandidates.clear()
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
            cardioReady = false
            lastCardioBpm = 0
            // Confirmed glasses: remember them so future connects skip the scan.
            val confirmedAddress = runCatching { g.device.address }.getOrNull()
            if (!confirmedAddress.isNullOrBlank()) {
                prefs.edit().putString(PREF_GLASSES_ADDR, confirmedAddress).apply()
            }
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

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleUplink(value)
        }

        // Android < 13 entry point; routed to the same parser.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleUplink(value)
        }
    }

    /**
     * Decode a GM frame coming from the glasses. Only the cardio readiness
     * announcement matters here: command 0x29, channel 0x4355, payload
     * `[version, EVENT_READY]`.
     */
    private fun handleUplink(frame: ByteArray) {
        if (frame.size < 9) return
        if ((frame[0].toInt() and 0xFF) != GmFrame.FRAME_HEAD_FIRST) return
        if ((frame[5].toInt() and 0xFF) != GmFrame.SERVICE_PLUGIN) return
        if ((frame[6].toInt() and 0xFF) != 0x29) return

        // A continuation frame starts with 0x01..; only the first one carries
        // the TLVs we need (the cardio payloads are tiny, so one frame is enough).
        val end = frame.size - 2
        var i = 7
        var channel = -1
        var data: ByteArray? = null
        while (i + 4 <= end) {
            val type = frame[i].toInt() and 0xFF
            val len = ((frame[i + 1].toInt() and 0xFF) shl 16) or
                ((frame[i + 2].toInt() and 0xFF) shl 8) or
                (frame[i + 3].toInt() and 0xFF)
            i += 4
            if (i + len > end) return
            when (type) {
                GmFrame.TLV_INT16 -> if (len == 2) {
                    channel = ((frame[i].toInt() and 0xFF) shl 8) or
                        (frame[i + 1].toInt() and 0xFF)
                }
                GmFrame.TLV_BYTES -> data = frame.copyOfRange(i, i + len)
            }
            i += len
        }

        val payload = data ?: return
        if (channel == GmFrame.CH_CARDIO_UP && payload.size >= 2 && payload[1].toInt() == 1) {
            if (!cardioReady) {
                cardioReady = true
                lastCardioBpm = 0
                listener.onGlassesStatus("Glasses cardio plugin ready.")
                listener.onCardioReady()
            }
            return
        }
        // Any other uplink (ACKs, Web Bridge events) is ignored for now.
    }

    // ---- Paired cardio plugin ------------------------------------------

    /** Push the resting / maximum bands the glasses use for their zones. */
    fun sendCardioConfig(restingBpm: Int, maxBpm: Int) {
        if (!cardioReady) return
        sendCardioPayload(GmFrame.cardioConfig(restingBpm, maxBpm))
    }

    /**
     * Stream one reading to the cardio glasses plugin. Throttled to one payload
     * every [CARDIO_PUSH_INTERVAL_MS]; the newest value always wins so the
     * display never lags behind.
     */
    fun showCardioReading(
        bpm: Int,
        restingBpm: Int,
        maxBpm: Int,
        contactBad: Boolean,
        trendBpm: Int
    ) {
        if (!cardioReady) return
        val zone = GmFrame.cardioZone(bpm, restingBpm, maxBpm)
        // The glasses ignore the trend byte; sending the last value keeps the
        // wire identical to PhoneSDK/examples/cardio/protocol.js.
        val trend = if (lastCardioBpm > 0) lastCardioBpm else bpm
        lastCardioBpm = bpm
        sendCardioPayload(GmFrame.cardioHeartRate(bpm, zone, contactBad, trend))
    }

    /** Report the sensor state (see protocol.js STATE_*). */
    fun sendCardioStatus(state: Int) {
        if (!cardioReady) return
        sendCardioPayload(GmFrame.cardioStatus(state))
    }

    private fun sendCardioPayload(payload: ByteArray) {
        if (!cardioReady) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cardioSentAt < CARDIO_PUSH_INTERVAL_MS) {
            cardioPending = payload
            handler.postDelayed(cardioFlush, CARDIO_PUSH_INTERVAL_MS)
            return
        }
        cardioSentAt = now
        cardioPending = null
        GmFrame.pluginMessage(
            eventId = nextEventId(),
            channel = GmFrame.CH_CARDIO_DOWN,
            payload = payload,
            maxFrameBytes = maxFrameForMtu()
        ).forEach { enqueue(it) }
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
        cardioReady = false
        cardioPending = null
        linking = false
        linkCandidate = -1
        candidates.clear()
        gmCandidates.clear()
        advertisedServices.clear()
        handler.removeCallbacks(cardioFlush)
        handler.removeCallbacks(scanTimeout)
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
