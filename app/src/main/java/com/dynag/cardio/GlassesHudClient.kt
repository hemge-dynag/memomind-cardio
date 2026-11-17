package com.dynag.cardio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
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
         * The glasses could not be identified automatically. [devices] is a
         * list of (address, name) pairs — the phone's bonded devices first,
         * then whatever the scan saw — for the user to pick the glasses once;
         * the choice is remembered for next time.
         */
        fun onGlassesChooser(devices: List<Pair<String, String>>)
    }

    private val tag = "GlassesHudClient"

    companion object {
        private const val OBJECT_HR = 1
        private const val OBJECT_METRICS = 2
        private const val OBJECT_TIME = 3
        // Object 4 is the plugin-probe line, kept apart from the workout rows.
        private const val OBJECT_PROBE = 4

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

        // Classic SPP command transport, the one the official App prefers.
        // The glasses never advertise the BLE GM service on every firmware,
        // and a phone that paired them over Classic can only reach them here.
        private const val UUID_GM_SPP = "00007033-0000-1000-8000-00805f9b34fb"
        private const val UUID_SPP = "00001101-0000-1000-8000-00805f9b34fb"
        // SPP frames may use the firmware ceiling of 512 bytes.
        private const val SPP_MAX_FRAME_BYTES = 512
        private const val SPP_CONNECT_TIMEOUT_MS = 12000
        // How long we wait for the cardio plugin's EVENT_READY before falling
        // back to the Web Bridge text channel.
        private const val PLUGIN_WAIT_MS = 6000L
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
    /** Set once the current GATT reached STATE_CONNECTED. */
    private var linkUp = false
    /** True once we gave up on the cardio plugin and draw with Web Bridge. */
    @Volatile
    private var textFallback = false
    private var framesSent = 0
    private var uplinksSeen = 0
    private var bridgeAcked = false
    /** Live Classic SPP link, when the glasses are reached over RFCOMM. */
    private var spp: BluetoothSocket? = null
    private var sppOut: OutputStream? = null
    private var sppReader: Thread? = null
    private val sppWriteLock = Any()
    /** TRANSPORT_AUTO retry already used for the current pick. */
    private var autoTransportTried = false
    /** Address of the current link attempt, for retries. */
    private var pickAddress: String? = null
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

        // The glasses do not advertise the GM service in every firmware, so a
        // scan cannot identify them. Phones that already know the glasses
        // (paired in Settings) get the picker straight away instead.
        val bonded = bondedDevices()
        if (bonded.isNotEmpty()) {
            listener.onGlassesStatus("Pick your glasses from the paired devices.")
            listener.onGlassesChooser(bonded)
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
        // peripheral, ask the user to pick the glasses — paired devices first,
        // then anything the scan saw.
        val scanned = orderedCandidates().map { it.first.address to it.second }
        stopScan()
        linking = false
        if (scanned.isEmpty()) {
            val bonded = bondedDevices()
            if (bonded.isEmpty()) {
                listener.onGlassesStatus("No MemoMind glasses found. Pair them in Settings, then retry.")
                return@Runnable
            }
            listener.onGlassesStatus("No glasses announced themselves. Pick from the paired devices.")
            listener.onGlassesChooser(bonded)
            return@Runnable
        }
        listener.onGlassesStatus("Glasses not auto-detected. Pick them in the list.")
        listener.onGlassesChooser(mergeBonded(scanned))
    }

    /** Paired devices first, then [scanned], without duplicates. */
    private fun mergeBonded(
        scanned: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        val bonded = bondedDevices()
        val seen = bonded.mapTo(HashSet()) { it.first }
        val extra = scanned.filter { seen.add(it.first) }
        return bonded + extra
    }

    /**
     * Devices already paired with the phone (Settings › Bluetooth). The
     * glasses never advertise the GM service, so this list is the most
     * reliable way to find them: the user picks once, the address is kept.
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<Pair<String, String>> {
        val ad = adapter ?: return emptyList()
        if (!ad.isEnabled) return emptyList()
        val bonded = runCatching { ad.bondedDevices }.getOrNull() ?: return emptyList()
        return bonded.map { d ->
            val name = runCatching { d.name }.getOrNull()
            val label = if (name.isNullOrBlank()) d.address else name
            d.address to label
        }
    }

    /** The picker was dismissed: allow a fresh Connect attempt. */
    fun cancelChooser() {
        linking = false
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
        autoTransportTried = false
        pickAddress = address
        val name = runCatching { device.name }.getOrNull() ?: address
        logDeviceTraits(device)
        listener.onGlassesStatus("Connecting to $name…")
        // A device bonded as CLASSIC (or DUAL) is reached over the Classic SPP
        // command service; its BLE GATT server is not exposed, which is why a
        // LE link ended on "Link failed (status 147)".
        if (device.type != android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE) {
            connectSpp(device, name)
            return
        }
        openGatt(device, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Open the Classic SPP command channel. The documented service is
     * 0x7033, with the standard 0x1101 SPP UUID as the App's fallback;
     * both are tried secure first, then insecure.
     */
    @SuppressLint("MissingPermission")
    private fun connectSpp(device: android.bluetooth.BluetoothDevice, name: String) {
        val ad = adapter
        if (ad == null) {
            listener.onGlassesStatus("No Bluetooth adapter.")
            return
        }
        runCatching { ad.cancelDiscovery() }
        listener.onGlassesStatus("Opening the Classic link to $name…")
        Thread {
            val sockets = listOf(
                UUID.fromString(UUID_GM_SPP) to true,
                UUID.fromString(UUID_SPP) to true,
                UUID.fromString(UUID_GM_SPP) to false
            )
            var lastError = "no RFCOMM service"
            for ((uuid, secure) in sockets) {
                val socket = try {
                    if (secure) {
                        device.createRfcommSocketToServiceRecord(uuid)
                    } else {
                        device.createInsecureRfcommSocketToServiceRecord(uuid)
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    continue
                }
                try {
                    runCatching { ad.cancelDiscovery() }
                    socket.connect()
                    handler.post { onSppUp(device, socket) }
                    return@Thread
                } catch (e: Exception) {
                    lastError = "status ${runCatching { e.message }.getOrNull()} ($uuid)"
                    runCatching { socket.close() }
                }
            }
            handler.post {
                linking = false
                listener.onGlassesStatus("Classic link failed: $lastError")
                listener.onGlassesStatus("Trying Bluetooth LE instead…")
                openGatt(device, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            }
        }.start()
    }

    /**
     * The cardio plugin usually announces EVENT_READY a moment after the link
     * comes up. If it never does, say so and draw through the Web Bridge text
     * channel instead, so the HUD is not left blank without explanation.
     */
    private fun armPluginWait() {
        handler.removeCallbacks(pluginWait)
        pluginWaitAt = 0L
        if (!textFallback) handler.postDelayed(pluginWait, PLUGIN_WAIT_MS)
    }

    private var pluginWaitAt = 0L
    private val pluginWait = Runnable {
        if (cardioReady || !ready) return@Runnable
        textFallback = true
        listener.onGlassesStatus(
            "No cardio plugin answered; testing the Web Bridge text channel."
        )
        listener.onGlassesStatus("Frames sent: $framesSent, uplinks seen: $uplinksSeen.")
        GmFrame.webBridgeText(
            eventId = nextEventId(),
            id = OBJECT_PROBE,
            x = 20,
            y = 250,
            width = 560,
            height = 60,
            border = 0,
            radius = 0,
            text = "HUD check",
            maxFrameBytes = maxFrameForMtu()
        ).forEach { enqueue(it) }
    }

    /** SPP is up: this is a confirmed glasses link, start the heartbeat. */
    @SuppressLint("MissingPermission")
    private fun onSppUp(device: android.bluetooth.BluetoothDevice, socket: BluetoothSocket) {
        if (gatt != null) {
            runCatching { gatt?.close() }
            gatt = null
        }
        spp = socket
        sppOut = runCatching { socket.outputStream }.getOrNull()
        if (sppOut == null) {
            listener.onGlassesStatus("Classic link has no output stream.")
            runCatching { socket.close() }
            spp = null
            linking = false
            return
        }
        linking = false
        linkUp = true
        ready = true
        cardioReady = false
        lastCardioBpm = 0
        mtu = SPP_MAX_FRAME_BYTES
        val addr = runCatching { device.address }.getOrNull()
        if (!addr.isNullOrBlank()) {
            prefs.edit().putString(PREF_GLASSES_ADDR, addr).apply()
        }
        listener.onGlassesStatus("Glasses connected over Classic SPP.")
        startSppReader(socket)
        armPluginWait()
        writeRaw(byteArrayOf(0xFA.toByte(), 0x00, 0x00, 0x09, 0x01, 0x01, 0x01, 0x01, 0x06))
        writeBusy = false
        listener.onGlassesReady()
    }

    /** Read the SPP byte stream and hand complete GM frames to the parser. */
    private fun startSppReader(socket: BluetoothSocket) {
        sppReader?.interrupt()
        val input: InputStream = runCatching { socket.inputStream }.getOrNull() ?: return
        val reader = Thread {
            val buffer = ByteArray(1024)
            var acc = ByteArray(0)
            try {
                while (spp === socket) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    acc += buffer.copyOf(n)
                    acc = drainStream(acc)
                }
            } catch (e: Exception) {
                Log.d(tag, "SPP reader ended: ${e.message}")
            }
            handler.post {
                if (spp === socket) onSppLost()
            }
        }
        reader.isDaemon = true
        sppReader = reader
        reader.start()
    }

    /** Extract every complete physical frame from [acc]. */
    private fun drainStream(acc: ByteArray): ByteArray {
        var offset = 0
        while (acc.size - offset >= 4) {
            val head = acc[offset].toInt() and 0xFF
            if (head != GmFrame.FRAME_HEAD_FIRST && head < 0x01) {
                offset++
                continue
            }
            val logical = ((acc[offset + 1].toInt() and 0xFF) shl 16) or
                ((acc[offset + 2].toInt() and 0xFF) shl 8) or
                (acc[offset + 3].toInt() and 0xFF)
            if (logical < 9 || logical > SPP_MAX_FRAME_BYTES) {
                Log.w(tag, "SPP: unsupported frame length $logical")
                offset++
                continue
            }
            if (acc.size - offset < logical) break
            val frame = acc.copyOfRange(offset, offset + logical)
            offset += logical
            handleUplink(frame)
        }
        return if (offset == 0) acc else acc.copyOfRange(offset, acc.size)
    }

    private fun onSppLost() {
        spp = null
        sppOut = null
        ready = false
        cardioReady = false
        cardioPending = null
        handler.removeCallbacks(cardioFlush)
        // A saved address that fails later is kept: the glasses may simply be
        // out of range, and re-picking them on every start would be noise.
        listener.onGlassesStatus("Glasses link lost.")
        listener.onGlassesDisconnected()
    }

    /** Close any previous handle, then start a fresh link to [device]. */
    @SuppressLint("MissingPermission")
    private fun openGatt(device: android.bluetooth.BluetoothDevice, transport: Int) {
        // Detach first: the closing handle may still deliver a DISCONNECTED
        // callback, which would otherwise cancel the link we start next.
        val previous = gatt
        gatt = null
        if (previous != null) {
            runCatching { previous.disconnect() }
            runCatching { previous.close() }
        }
        linkUp = false
        gatt = device.connectGatt(context, false, gattCallback, transport)
    }

    /**
     * The glasses are a bonded, often dual-mode device. Printing its type,
     * bond state and classic UUIDs turns a bare "did not answer" into a
     * diagnosable line in the journal.
     */
    @SuppressLint("MissingPermission")
    private fun logDeviceTraits(d: android.bluetooth.BluetoothDevice) {
        val type = when (d.type) {
            android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE -> "LE"
            android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC (SPP)"
            android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL (SPP first)"
            else -> "UNKNOWN"
        }
        val sppPresent = runCatching {
            d.uuids?.any {
                val t = it.uuid.toString().lowercase()
                t == UUID_GM_SPP || t == UUID_SPP
            } ?: false
        }.getOrNull() ?: false
        val bond = when (d.bondState) {
            android.bluetooth.BluetoothDevice.BOND_BONDED -> "bonded"
            android.bluetooth.BluetoothDevice.BOND_BONDING -> "bonding"
            else -> "not bonded"
        }
        listener.onGlassesStatus("Link target ${d.address} ($type, $bond).")
        if (sppPresent) listener.onGlassesStatus("The phone records an SPP service for it.")
        Log.d(tag, "target ${d.address} type=$type bond=$bond spp=$sppPresent")
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
        pickAddress = device.address
        openGatt(device, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    /** One-shot retry of the same pick with TRANSPORT_AUTO. */
    @SuppressLint("MissingPermission")
    private fun retryAuto() {
        val ad = adapter ?: return
        val addr = pickAddress ?: return
        val device = runCatching { ad.getRemoteDevice(addr) }.getOrNull() ?: return
        linking = true
        linkCandidate = 0
        listener.onGlassesStatus("Retrying with the system transport…")
        openGatt(device, android.bluetooth.BluetoothDevice.TRANSPORT_AUTO)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // A closed BluetoothGatt can still deliver this callback after we
            // already opened a new link. Acting on it cancelled fresh attempts
            // ("No MemoMind glasses answered" right after picking them).
            if (g !== gatt) {
                Log.d(tag, "ignoring stale callback status=$status state=$newState")
                runCatching { g.close() }
                return
            }
            Log.d(tag, "connection status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linkUp = true
                    // status 133 can still accompany a usable link; keep going.
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
                    gatt = null
                    val failed = status != BluetoothGatt.GATT_SUCCESS && !linkUp
                    if (failed) listener.onGlassesStatus("Link failed (status $status).")
                    if (linking) {
                        linking = false
                        // A dual-mode bonded device may refuse TRANSPORT_LE
                        // outright; one AUTO retry covers that firmware.
                        if (failed && !autoTransportTried && linkCandidate <= 0 &&
                            !pickAddress.isNullOrBlank()
                        ) {
                            autoTransportTried = true
                            retryAuto()
                            return
                        }
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
            if (g !== gatt) {
                Log.d(tag, "ignoring stale discovery (status $status)")
                return
            }
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
                // Direct picks have no scan list to walk through; offer the
                // paired devices again instead of failing silently.
                if (orderedCandidates().isEmpty()) {
                    pickAddress = null
                    val bonded = bondedDevices()
                    if (bonded.isNotEmpty()) {
                        listener.onGlassesStatus("That device is not the glasses. Pick another one.")
                        listener.onGlassesChooser(bonded)
                        return
                    }
                }
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
            armPluginWait()
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
        uplinksSeen++
        if (frame.size < 9) {
            Log.d(tag, "uplink too short: ${frame.size}")
            return
        }
        val head = frame[0].toInt() and 0xFF
        val service = frame[5].toInt() and 0xFF
        val command = frame[6].toInt() and 0xFF
        Log.d(tag, "uplink #$uplinksSeen head=$head svc=$service cmd=$command size=${frame.size}")
        // Show the first few unusual uplinks so the journal explains silence.
        if (uplinksSeen <= 8 && (head != GmFrame.FRAME_HEAD_FIRST || service != GmFrame.SERVICE_PLUGIN)) {
            listener.onGlassesStatus("Uplink #$uplinksSeen: head=$head svc=$service cmd=$command")
        }
        if (head != GmFrame.FRAME_HEAD_FIRST) return
        if (service != GmFrame.SERVICE_PLUGIN) return
        if (command != 0x29) return

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
        if (channel != GmFrame.CH_CARDIO_UP && !bridgeAcked) {
            // Command 0x29 with a non-cardio channel is the Web Bridge plugin
            // answering a delivery: text drawing is possible on this device.
            bridgeAcked = true
            val hex = payload.take(16).joinToString("") { "%02x".format(it) }
            listener.onGlassesStatus("Web Bridge answered (ch=0x%04x data=$hex).".format(channel))
        }
        if (channel == GmFrame.CH_CARDIO_UP && payload.size >= 2 && payload[1].toInt() == 1) {
            if (!cardioReady) {
                cardioReady = true
                lastCardioBpm = 0
                listener.onGlassesStatus("Glasses cardio plugin ready.")
                listener.onCardioReady()
            }
            return
        }
        // Anything else on the plugin channel helps identify the payload layout.
        if (uplinksSeen <= 12) {
            val hex = payload.take(12).joinToString("") { "%02x".format(it) }
            listener.onGlassesStatus("Plugin event ch=0x%04x data=$hex".format(channel))
        }
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
        if (!cardioReady) {
            // Without the paired plugin, Web Bridge text is the only path: draw
            // one HR object per [CARDIO_PUSH_INTERVAL_MS], newest value wins.
            if (textFallback && ready) scheduleTextReading(bpm)
            return
        }
        val zone = GmFrame.cardioZone(bpm, restingBpm, maxBpm)
        // The glasses ignore the trend byte; sending the last value keeps the
        // wire identical to PhoneSDK/examples/cardio/protocol.js.
        val trend = if (lastCardioBpm > 0) lastCardioBpm else bpm
        lastCardioBpm = bpm
        sendCardioPayload(GmFrame.cardioHeartRate(bpm, zone, contactBad, trend))
    }

    private var pendingTextBpm = 0
    private var textSentAt = 0L

    /**
     * Text fallback is throttled like the plugin payload: a sensor can fire
     * several times per second and the HUD only needs the newest value.
     */
    private fun scheduleTextReading(bpm: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - textSentAt >= CARDIO_PUSH_INTERVAL_MS) {
            textSentAt = now
            lastCardioBpm = bpm
            sendText(OBJECT_HR, "$bpm BPM")
            return
        }
        pendingTextBpm = bpm
        handler.removeCallbacks(textFlush)
        handler.postDelayed(textFlush, CARDIO_PUSH_INTERVAL_MS)
    }

    private val textFlush = Runnable {
        val bpm = pendingTextBpm
        if (bpm <= 0 || !textFallback) return@Runnable
        pendingTextBpm = 0
        textSentAt = android.os.SystemClock.elapsedRealtime()
        lastCardioBpm = bpm
        sendText(OBJECT_HR, "$bpm BPM")
    }

    /** Report the sensor state (see protocol.js STATE_*). */
    fun sendCardioStatus(state: Int) {
        if (!cardioReady) return
        sendCardioPayload(GmFrame.cardioStatus(state))
    }

    /** True once the plugin missed its greeting and text drawing took over. */
    val usingTextFallback: Boolean get() = textFallback

    /** True when a Web Bridge delivery ACK came back for one of our frames. */
    val webBridgeAlive: Boolean get() = bridgeAcked

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
        if (sppOut != null) return SPP_MAX_FRAME_BYTES
        return (mtu - 3).coerceAtLeast(20)
    }

    private fun enqueue(frame: ByteArray) {
        writeQueue.add(frame)
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeBusy) return
        if (sppOut == null) {
            if (gatt == null || commandChar == null) return
        }
        val frame = writeQueue.poll() ?: return
        writeBusy = true
        writeRaw(frame)
    }

    @SuppressLint("MissingPermission")
    private fun writeRaw(frame: ByteArray) {
        val out = sppOut
        if (out != null) {
            // Classic SPP is a byte stream: one complete GM frame per write.
            try {
                synchronized(sppWriteLock) { out.write(frame); out.flush() }
            } catch (e: Exception) {
                Log.w(tag, "SPP write failed", e)
                onSppLost()
                return
            }
            framesSent++
            writeBusy = false
            drainWriteQueue()
            return
        }
        val g = gatt ?: return
        val ch = commandChar ?: return
        framesSent++
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
        linkUp = false
        pickAddress = null
        autoTransportTried = false
        candidates.clear()
        gmCandidates.clear()
        advertisedServices.clear()
        handler.removeCallbacks(cardioFlush)
        handler.removeCallbacks(pluginWait)
        handler.removeCallbacks(textFlush)
        pendingTextBpm = 0
        handler.removeCallbacks(scanTimeout)
        writeQueue.clear()
        sppReader?.interrupt()
        sppReader = null
        sppOut?.let { runCatching { it.close() } }
        sppOut = null
        spp?.let {
            spp = null
            runCatching { it.close() }
        }
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {
                Log.w(tag, "Error closing glasses GATT", e)
            }
        }
        gatt = null
        linkUp = false
    }
}
