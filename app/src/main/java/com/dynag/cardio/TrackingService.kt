package com.dynag.cardio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Keeps the workout alive while the phone screen is off.
 *
 * GPS fixes and the screen-off Doze mode stop arriving for a foreground-only
 * activity as soon as the display is turned off, which left the glasses HUD
 * frozen on the last speed / time while the heart-rate sensor kept streaming.
 * A foreground service with a `location` type keeps the GNSS updates flowing,
 * and also drains the glasses write queue once the activity is gone.
 */
class TrackingService : Service(),
    LocationTracker.Listener,
    GlassesHudClient.Listener,
    HeartRateManager.Listener {

    private lateinit var heartRate: HeartRateManager
    private lateinit var location: LocationTracker
    private lateinit var glasses: GlassesHudClient
    private val handler = Handler(Looper.getMainLooper())

    private var lastBpm = 0
    private var lastContactBad = false
    private var sensorReady = false
    private var pushEnabled = false
    private var lastDistance = 0.0
    private var lastSpeedKmh = 0.0
    private var lastAverageKmh = 0.0
    private var lastElapsedMs = 0L
    private var lastTrackingSentAt = 0L
    private var lastHudStatusAt = 0L

    private val restingBpm = 60
    private val maxBpm = 190

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        heartRate = HeartRateManager(this, this)
        location = LocationTracker(this, this)
        glasses = GlassesHudClient(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground("Suivi démarré")
                connectGlasses()
                heartRate.connectSaved()
                location.start()
                startTrackingTicker()
            }
            ACTION_STOP -> stopSelf()
            else -> startAsForeground("Suivi actif")
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The screen going off or the task being swiped away must not stop a
        // workout: keep going, the notification is the way back.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (location.isRunning) location.stop()
        heartRate.stop()
        glasses.sendCardioTracking(false, 0L, 0.0, 0.0)
        glasses.disconnect()
        super.onDestroy()
    }

    // ---- foreground notification ---------------------------------------

    private fun startAsForeground(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        val km = lastDistance / 1000.0
        val totalSec = lastElapsedMs / 1000
        val text = String.format(
            Locale.US,
            "%d BPM · %.2f km · %.1f km/h · %d:%02d",
            lastBpm, km, lastSpeedKmh, totalSec / 60, totalSec % 60
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_tracking)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    // ---- tracking loop --------------------------------------------------

    /**
     * The HUD must keep moving even when GPS fixes are sparse (tunnel, indoor
     * start): a one-second tick re-sends the current elapsed time and the last
     * known speed so the glasses' timer never freezes.
     */
    private fun startTrackingTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (location.isRunning) {
                // The tracker owns the authoritative start time; the tick only
                // makes the HUD clock advance between two GPS fixes.
                lastElapsedMs = location.elapsedMillis
                pushTracking()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun pushTracking() {
        if (!pushEnabled) return
        // Also re-assert the stream if the plugin was (re)started mid-run.
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrackingSentAt >= TRACKING_RESEND_MS) {
            lastTrackingSentAt = now
            glasses.sendCardioTracking(
                location.isRunning, lastElapsedMs, lastSpeedKmh, lastDistance
            )
        }
    }

    private fun connectGlasses() {
        // connect() reuses the address persisted by a previous run, so the
        // service reaches the same glasses without the in-app chooser.
        glasses.connect()
    }

    // ---- LocationTracker.Listener ---------------------------------------

    override fun onLocationStatus(message: String) {
        Log.i(TAG, message)
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
        pushTracking()
        val now = SystemClock.elapsedRealtime()
        if (now - lastHudStatusAt >= HUD_NOTIFY_MS) {
            lastHudStatusAt = now
            updateNotification()
        }
    }

    // ---- GlassesHudClient.Listener --------------------------------------

    override fun onGlassesStatus(message: String) {
        Log.i(TAG, message)
    }

    override fun onGlassesReady() {
        pushEnabled = true
        glasses.sendCardioConfig(restingBpm, maxBpm)
        glasses.sendCardioStatus(if (sensorReady) STATE_READING else STATE_CONNECTING)
        glasses.showMetrics(lastBpm, lastDistance, lastSpeedKmh, lastAverageKmh, lastElapsedMs)
        pushTracking()
    }

    override fun onGlassesDisconnected() {
        pushEnabled = false
    }

    override fun onCardioReady() {
        glasses.sendCardioConfig(restingBpm, maxBpm)
        glasses.sendCardioStatus(if (sensorReady) STATE_READING else STATE_CONNECTING)
        pushTracking()
    }

    override fun onGlassesChooser(devices: List<Pair<String, String>>) {
        Log.i(TAG, "Chooser requested in service, ignoring")
    }

    // ---- HeartRateManager.Listener --------------------------------------

    override fun onSensorStatus(message: String) {
        Log.i(TAG, message)
    }

    override fun onSensorsDiscovered(sensors: List<BleSensor>) {
        if (sensors.size == 1) heartRate.connect(sensors[0])
    }

    override fun onSensorReady() {
        sensorReady = true
        glasses.sendCardioConfig(restingBpm, maxBpm)
        glasses.sendCardioStatus(if (lastBpm > 0) STATE_READING else STATE_CONNECTING)
        lastBpm = 0
    }

    override fun onSensorDisconnected() {
        sensorReady = false
        glasses.sendCardioStatus(STATE_IDLE)
    }

    override fun onSensorError(message: String) {
        sensorReady = false
        Log.w(TAG, message)
        // A lost sensor must not kill the workout; the strap reconnects when
        // it comes back, the GPS part keeps tracking.
    }

    override fun onHeartRate(bpm: Int, contactDetected: Boolean?) {
        val firstBeat = lastBpm == 0
        lastBpm = bpm
        lastContactBad = contactDetected == false
        glasses.showCardioReading(bpm, restingBpm, maxBpm, lastContactBad, bpm)
        if (firstBeat) glasses.sendCardioStatus(STATE_READING)
    }

    companion object {
        private const val TAG = "CardioTracking"
        private const val CHANNEL_ID = "cardio_tracking"
        private const val NOTIF_ID = 41
        private const val TICK_MS = 1000L
        private const val TRACKING_RESEND_MS = 500L
        private const val HUD_NOTIFY_MS = 3000L

        private const val STATE_CONNECTING = 1
        private const val STATE_READING = 2
        private const val STATE_IDLE = 0

        const val ACTION_START = "com.dynag.cardio.action.START_TRACKING"
        const val ACTION_STOP = "com.dynag.cardio.action.STOP_TRACKING"

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

