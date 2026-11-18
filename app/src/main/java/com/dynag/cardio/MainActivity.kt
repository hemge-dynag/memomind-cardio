package com.dynag.cardio

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * MemoMind Cardio — standalone companion app.
 *
 * Reads live heart rate from any Bluetooth LE source that exposes the standard
 * Heart Rate service (0x180D): chest straps (Polar H10, Garmin, Wahoo…) and
 * wearables that broadcast optical HR over the standard Bluetooth Heart Rate
 * Profile (Fitbit Charge 6, Google Fitbit Air, Google Pixel Watch 2+).
 *
 * Distance, speed and elapsed time come from the phone's GPS, since a
 * heart-rate source carries no motion data. The combined workout metrics are
 * mirrored onto the MemoMind smart glasses HUD over Bluetooth LE.
 *
 * Two displays are driven at once over the GM plugin service (0x0F / 0x28):
 * the paired **cardio glasses plugin** (channel 0x4344, drawn natively by the
 * `.gmp`), and the legacy Web Bridge text channel (channel 2) for when no
 * cardio plugin is running.
 *
 * This app runs outside the MemoMind plugin ecosystem: it uses only public
 * Bluetooth protocols (standard Heart Rate service for the sensor, and the GM
 * plugin / Web Bridge service for the glasses).
 */
class MainActivity : AppCompatActivity(),
    HeartRateManager.Listener,
    LocationTracker.Listener,
    GlassesHudClient.Listener {

    private lateinit var bpmText: TextView
    private lateinit var distText: TextView
    private lateinit var speedText: TextView
    private lateinit var timeText: TextView
    private lateinit var avgText: TextView
    private lateinit var zoneText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnSensor: Button
    private lateinit var btnGlasses: Button
    private lateinit var btnTrack: Button
    private lateinit var btnPush: Button
    private lateinit var btnCopyLog: Button

    private lateinit var heartRate: HeartRateManager
    private lateinit var location: LocationTracker
    private lateinit var glasses: GlassesHudClient

    private var lastBpm: Int = 0
    private var lastDistance = 0.0
    private var lastSpeedKmh = 0.0
    private var lastAverageKmh = 0.0
    private var lastElapsedMs = 0L
    private var pushEnabled = false
    private var sensorReady = false

    /** True while a saved-sensor reconnect is in flight, so a failure can fall
     *  back to a fresh scan once instead of looping. */
    private var reconnectingSaved = false

    /** Bands the glasses use for their zone display (Karvonen). */
    private val restingBpm = 60
    private val maxBpm = 190

    /** True when the sensor last reported poor skin contact. */
    private var lastContactBad = false

    private companion object {
        // cardio plugin states (see PhoneSDK/examples/cardio/protocol.js)
        const val STATE_IDLE = 0
        const val STATE_CONNECTING = 1
        const val STATE_READING = 2
        const val STATE_ERROR = 4
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            onPermissionsGranted()
        } else {
            toast(getString(R.string.msg_permissions_required))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bpmText = findViewById(R.id.bpmText)
        distText = findViewById(R.id.distText)
        speedText = findViewById(R.id.speedText)
        timeText = findViewById(R.id.timeText)
        avgText = findViewById(R.id.avgText)
        zoneText = findViewById(R.id.zoneText)
        subtitleText = findViewById(R.id.subtitleText)
        statusText = findViewById(R.id.statusText)
        btnSensor = findViewById(R.id.btnSensor)
        btnGlasses = findViewById(R.id.btnGlasses)
        btnTrack = findViewById(R.id.btnTrack)
        btnPush = findViewById(R.id.btnPush)
        btnCopyLog = findViewById(R.id.btnCopyLog)

        heartRate = HeartRateManager(this, this)
        location = LocationTracker(this, this)
        glasses = GlassesHudClient(this, this)

        btnSensor.setOnClickListener {
            ensurePermissions {
                if (heartRate.isConnected) {
                    heartRate.stop()
                    appendStatus(getString(R.string.msg_sensor_released))
                } else {
                    // Reconnect to the remembered sensor first; only scan when
                    // none is saved. Long-press to forget and re-discover.
                    val saved = heartRate.savedSensor
                    if (heartRate.connectSaved()) {
                        reconnectingSaved = true
                        appendStatus(
                            getString(
                                R.string.reconnecting_sensor,
                                saved?.name?.ifBlank { saved.address } ?: "capteur"
                            )
                        )
                    } else {
                        heartRate.scanForSensors()
                    }
                }
            }
        }
        btnSensor.setOnLongClickListener {
            ensurePermissions {
                heartRate.stop()
                heartRate.forgetSaved()
                appendStatus(getString(R.string.msg_forgot_sensor))
                heartRate.scanForSensors()
            }
            true
        }
        btnGlasses.setOnClickListener {
            ensurePermissions { glasses.connect() }
        }
        btnTrack.setOnClickListener {
            ensurePermissions {
                if (location.isRunning) {
                    location.stop()
                    glasses.sendCardioTracking(false, 0L, 0.0, 0.0)
                    btnTrack.text = getString(R.string.btn_track)
                } else {
                    location.start()
                    btnTrack.text = getString(R.string.btn_track_stop)
                }
            }
        }
        btnPush.setOnClickListener {
            pushMetrics()
            appendStatus(getString(R.string.msg_pushed))
        }
        btnCopyLog.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Cardio log", statusText.text))
            appendStatus(getString(R.string.msg_log_copied))
        }
    }

    private fun requiredPermissions(): Array<String> {
        val location = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                *location
            )
        } else {
            location
        }
    }

    private fun ensurePermissions(action: () -> Unit) {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private var pendingAction: (() -> Unit)? = null

    private fun onPermissionsGranted() {
        pendingAction?.invoke()
        pendingAction = null
    }

    private fun pushMetrics() {
        if (!pushEnabled) return
        glasses.showMetrics(lastBpm, lastDistance, lastSpeedKmh, lastAverageKmh, lastElapsedMs)
        glasses.sendCardioTracking(
            location.isRunning, lastElapsedMs, lastSpeedKmh, lastDistance
        )
    }

    // ---- HeartRateManager.Listener ------------------------------------

    override fun onSensorStatus(message: String) {
        runOnUiThread { appendStatus(message) }
    }

    override fun onSensorsDiscovered(sensors: List<BleSensor>) {
        runOnUiThread {
            if (sensors.isEmpty()) {
                appendStatus(getString(R.string.msg_no_device_found))
                return@runOnUiThread
            }
            if (sensors.size == 1) {
                heartRate.connect(sensors[0])
                return@runOnUiThread
            }
            val labels = sensors.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_choose_sensor)
                .setItems(labels) { _, which -> heartRate.connect(sensors[which]) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    override fun onSensorReady() {
        runOnUiThread {
            reconnectingSaved = false
            sensorReady = true
            appendStatus(getString(R.string.msg_sensor_ready))
            glasses.sendCardioConfig(restingBpm, maxBpm)
            // CONNECTING until a real measurement lands: a strap that reports
            // "ready" but never sends a beat used to leave the glasses on a
            // READING page with no value.
            glasses.sendCardioStatus(if (lastBpm > 0) STATE_READING else STATE_CONNECTING)
            lastBpm = 0
        }
    }

    override fun onSensorDisconnected() {
        runOnUiThread {
            sensorReady = false
            subtitleText.text = "Capteur inactif"
            if (reconnectingSaved) {
                reconnectingSaved = false
                appendStatus(getString(R.string.saved_sensor_missing))
                heartRate.forgetSaved()
                ensurePermissions { heartRate.scanForSensors() }
                return@runOnUiThread
            }
            appendStatus(getString(R.string.msg_sensor_disconnected))
            glasses.sendCardioStatus(STATE_IDLE)
        }
    }

    override fun onSensorError(message: String) {
        runOnUiThread {
            sensorReady = false
            if (reconnectingSaved) {
                // The remembered sensor could not be reached (moved, paired
                // elsewhere, powered off). Forget it and fall back to a scan.
                reconnectingSaved = false
                appendStatus(getString(R.string.saved_sensor_unreachable))
                heartRate.forgetSaved()
                ensurePermissions { heartRate.scanForSensors() }
                return@runOnUiThread
            }
            appendStatus(message)
            glasses.sendCardioStatus(STATE_ERROR)
            toast(message)
        }
    }

    override fun onHeartRate(bpm: Int, contactDetected: Boolean?) {
        // Called on a binder thread: the glasses client is thread-safe, the
        // widgets are not, so only the UI part is posted.
        val firstBeat = lastBpm == 0
        lastBpm = bpm
        lastContactBad = contactDetected == false
        glasses.showCardioReading(bpm, restingBpm, maxBpm, lastContactBad, bpm)
        if (firstBeat && pushEnabled) glasses.sendCardioStatus(STATE_READING)
        runOnUiThread {
            bpmText.text = bpm.toString()
            updateZone(bpm)
            subtitleText.text = "Capteur connecté"
            if (firstBeat) appendStatus(getString(R.string.msg_first_beat))
            if (pushEnabled && sensorReady) pushMetrics()
        }
    }

    // ---- LocationTracker.Listener -------------------------------------

    override fun onLocationStatus(message: String) {
        runOnUiThread { appendStatus(message) }
    }

    override fun onMetrics(
        distanceMeters: Double,
        speedMps: Float,
        averageSpeedMps: Double,
        elapsedMillis: Long
    ) {
        lastDistance = distanceMeters
        lastSpeedKmh = speedMps * 3.6
        lastAverageKmh = averageSpeedMps * 3.6
        lastElapsedMs = elapsedMillis
        runOnUiThread {
            distText.text = formatDistance(distanceMeters)
            speedText.text = formatSpeed(lastSpeedKmh)
            val totalSec = elapsedMillis / 1000
            timeText.text = String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
            avgText.text = formatSpeed(lastAverageKmh)
            if (pushEnabled && sensorReady) pushMetrics()
        }
    }

    // ---- GlassesHudClient.Listener ------------------------------------

    override fun onGlassesStatus(message: String) {
        runOnUiThread { appendStatus(message) }
    }

    override fun onGlassesReady() {
        runOnUiThread {
            pushEnabled = true
            btnPush.isEnabled = true
            appendStatus(getString(R.string.msg_glasses_ready))
            // The cardio plugin announces EVENT_READY on its own (shortly after
            // the connection event); onCardioReady() sends the config then.
        }
    }

    override fun onGlassesDisconnected() {
        runOnUiThread {
            pushEnabled = false
            btnPush.isEnabled = false
        }
    }

    override fun onCardioReady() {
        runOnUiThread {
            glasses.sendCardioConfig(restingBpm, maxBpm)
            glasses.sendCardioStatus(if (sensorReady) STATE_READING else STATE_CONNECTING)
            appendStatus(getString(R.string.msg_cardio_ready))
        }
    }

    private var chooserDialog: AlertDialog? = null

    override fun onGlassesChooser(devices: List<Pair<String, String>>) {
        runOnUiThread {
            // Repeated Connect taps must not stack identical dialogs.
            if (chooserDialog?.isShowing == true) return@runOnUiThread
            val labels = devices.map { (addr, name) -> "$name\n$addr" }.toTypedArray()
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.dialog_choose_glasses)
                .setItems(labels) { _, which ->
                    val (addr, name) = devices[which]
                    appendStatus("Appareil sélectionné : $name ($addr).")
                    glasses.connectToAddress(addr)
                }
                .setNegativeButton(R.string.dialog_cancel) { _, _ -> glasses.cancelChooser() }
                .setOnCancelListener { glasses.cancelChooser() }
                .create()
            chooserDialog = dialog
            dialog.show()
        }
    }

    // -------------------------------------------------------------------

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000.0) {
            String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
        } else {
            String.format(Locale.getDefault(), "%.0f m", meters)
        }

    private fun formatSpeed(kmh: Double): String =
        String.format(Locale.getDefault(), "%.1f km/h", kmh)

    /** Karvonen zone label, mirroring the glasses (see GmFrame.cardioZone). */
    private fun updateZone(bpm: Int) {
        if (bpm <= 0) {
            zoneText.text = getString(R.string.zone_caption)
            return
        }
        when (GmFrame.cardioZone(bpm, restingBpm, maxBpm)) {
            1 -> setZone("Repos", R.color.text_secondary)
            2 -> setZone("Modérée", R.color.ok)
            3 -> setZone("Effort", R.color.warn)
            4 -> setZone("Maximum", R.color.bad)
            else -> setZone("Zone inconnue", R.color.text_secondary)
        }
    }

    private fun setZone(label: String, colorRes: Int) {
        zoneText.text = label
        zoneText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun appendStatus(line: String) {
        statusText.append(line + "\n")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        heartRate.stop()
        location.stop()
        glasses.sendCardioTracking(false, 0L, 0.0, 0.0)
        glasses.disconnect()
    }
}
