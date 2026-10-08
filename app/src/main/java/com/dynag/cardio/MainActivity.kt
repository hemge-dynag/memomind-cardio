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

/**
 * MemoMind Cardio — standalone companion app.
 *
 * Reads live heart rate from any Bluetooth LE source that exposes the standard
 * Heart Rate service (0x180D): chest straps (Polar H10, Garmin, Wahoo…) and
 * wearables that broadcast optical HR over the standard Bluetooth Heart Rate
 * Profile (Fitbit Charge 6, Google Fitbit Air, Google Pixel Watch 2+). It then
 * mirrors the BPM onto the MemoMind smart glasses HUD over Bluetooth LE.
 *
 * This app runs outside the MemoMind plugin ecosystem: it uses only public
 * Bluetooth protocols (standard Heart Rate service for the sensor, and the GM /
 * Web Bridge channel for the glasses).
 */
class MainActivity : AppCompatActivity(), HeartRateManager.Listener, GlassesHudClient.Listener {

    private lateinit var bpmText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnSensor: Button
    private lateinit var btnGlasses: Button
    private lateinit var btnPush: Button

    private lateinit var heartRate: HeartRateManager
    private lateinit var glasses: GlassesHudClient

    private var lastBpm: Int = 0
    private var pushEnabled = false
    private var sensorReady = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            onPermissionsGranted()
        } else {
            toast("Bluetooth permissions are required.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bpmText = findViewById(R.id.bpmText)
        statusText = findViewById(R.id.statusText)
        btnSensor = findViewById(R.id.btnSensor)
        btnGlasses = findViewById(R.id.btnGlasses)
        btnPush = findViewById(R.id.btnPush)

        heartRate = HeartRateManager(this, this)
        glasses = GlassesHudClient(this, this)

        btnSensor.setOnClickListener {
            ensurePermissions { heartRate.scanForSensors() }
        }
        btnGlasses.setOnClickListener {
            ensurePermissions { glasses.connect() }
        }
        btnPush.setOnClickListener {
            if (lastBpm > 0) {
                glasses.showHeartRate(lastBpm)
                appendStatus("Sent $lastBpm BPM to glasses.")
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
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
            if (pushEnabled && sensorReady) {
                glasses.showHeartRate(bpm)
            }
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
        glasses.disconnect()
    }
}
