package com.dynag.cardio

import android.Manifest
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
 * This app runs outside the MemoMind plugin ecosystem: it uses only public
 * Bluetooth protocols (standard Heart Rate service for the sensor, and the GM /
 * Web Bridge channel for the glasses).
 */
class MainActivity : AppCompatActivity(),
    HeartRateManager.Listener,
    LocationTracker.Listener,
    GlassesHudClient.Listener {

    private lateinit var bpmText: TextView
    private lateinit var distText: TextView
    private lateinit var speedText: TextView
    private lateinit var timeText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnSensor: Button
    private lateinit var btnGlasses: Button
    private lateinit var btnTrack: Button
    private lateinit var btnPush: Button

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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            onPermissionsGranted()
        } else {
            toast("Bluetooth and location permissions are required.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bpmText = findViewById(R.id.bpmText)
        distText = findViewById(R.id.distText)
        speedText = findViewById(R.id.speedText)
        timeText = findViewById(R.id.timeText)
        statusText = findViewById(R.id.statusText)
        btnSensor = findViewById(R.id.btnSensor)
        btnGlasses = findViewById(R.id.btnGlasses)
        btnTrack = findViewById(R.id.btnTrack)
        btnPush = findViewById(R.id.btnPush)

        heartRate = HeartRateManager(this, this)
        location = LocationTracker(this, this)
        glasses = GlassesHudClient(this, this)

        btnSensor.setOnClickListener {
            ensurePermissions { heartRate.scanForSensors() }
        }
        btnGlasses.setOnClickListener {
            ensurePermissions { glasses.connect() }
        }
        btnTrack.setOnClickListener {
            ensurePermissions {
                if (location.isRunning) {
                    location.stop()
                    btnTrack.text = "Start tracking"
                } else {
                    location.start()
                    btnTrack.text = "Stop tracking"
                }
            }
        }
        btnPush.setOnClickListener {
            pushMetrics()
            appendStatus("Pushed current metrics to glasses.")
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
    }

    // ---- HeartRateManager.Listener ------------------------------------

    override fun onSensorStatus(message: String) {
        runOnUiThread { appendStatus(message) }
    }

    override fun onSensorsDiscovered(sensors: List<BleSensor>) {
        runOnUiThread {
            if (sensors.isEmpty()) {
                appendStatus("No device found. Wake the sensor or start HR sharing, then retry.")
                return@runOnUiThread
            }
            if (sensors.size == 1) {
                heartRate.connect(sensors[0])
                return@runOnUiThread
            }
            val labels = sensors.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Choose heart-rate source")
                .setItems(labels) { _, which -> heartRate.connect(sensors[which]) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onSensorReady() {
        runOnUiThread {
            sensorReady = true
            appendStatus("Heart-rate sensor ready.")
        }
    }

    override fun onSensorDisconnected() {
        runOnUiThread {
            sensorReady = false
            appendStatus("Heart-rate sensor disconnected.")
        }
    }

    override fun onSensorError(message: String) {
        runOnUiThread {
            sensorReady = false
            appendStatus(message)
            toast(message)
        }
    }

    override fun onHeartRate(bpm: Int, contactDetected: Boolean?) {
        lastBpm = bpm
        runOnUiThread {
            bpmText.text = bpm.toString()
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
            distText.text = String.format(Locale.US, "%.2f km", distanceMeters / 1000.0)
            speedText.text = String.format(Locale.US, "%.1f km/h", lastSpeedKmh)
            val totalSec = elapsedMillis / 1000
            timeText.text = String.format(
                Locale.US, "%d:%02d · %.1f km/h avg", totalSec / 60, totalSec % 60, lastAverageKmh
            )
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
            appendStatus("Glasses ready. Live push enabled.")
        }
    }

    override fun onGlassesDisconnected() {
        runOnUiThread {
            pushEnabled = false
            btnPush.isEnabled = false
        }
    }

    // -------------------------------------------------------------------

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
        glasses.disconnect()
    }
}
