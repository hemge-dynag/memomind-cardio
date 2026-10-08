package com.dynag.cardio

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.max

/**
 * Tracks distance, current speed, average speed and elapsed time using the
 * phone's GPS.
 *
 * A Bluetooth heart-rate source (chest strap or wearable broadcasting HR) only
 * reports heart beats — it carries no motion data, so speed and distance cannot
 * come from it. The phone's GNSS receiver is the practical source, exactly as in
 * a running/cycling app.
 *
 * Distance is accumulated from successive GPS fixes with [Location.distanceBetween];
 * current speed prefers the receiver's Doppler speed ([Location.hasSpeed]) and
 * falls back to position deltas when unavailable.
 */
class LocationTracker(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onLocationStatus(message: String)
        fun onMetrics(
            distanceMeters: Double,
            speedMps: Float,
            averageSpeedMps: Double,
            elapsedMillis: Long
        )
    }

    private val tag = "LocationTracker"
    private val handler = Handler(Looper.getMainLooper())

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var running = false
    private var lastLocation: Location? = null
    private var distanceMeters = 0.0
    private var startElapsed = 0L

    val isRunning: Boolean
        get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        val lm = locationManager
        if (lm == null) {
            listener.onLocationStatus("Location service unavailable.")
            return
        }
        val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!gpsEnabled) {
            listener.onLocationStatus("GPS is off. Please enable location.")
            return
        }
        reset()
        running = true
        listener.onLocationStatus("Tracking started (GPS).")
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            running = false
            Log.w(tag, "requestLocationUpdates failed", e)
            listener.onLocationStatus("Could not start GPS: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.w(tag, "removeUpdates failed", e)
        }
        listener.onLocationStatus("Tracking stopped.")
        emit(0f)
    }

    /** Zero the counters without stopping an active track. */
    fun reset() {
        lastLocation = null
        distanceMeters = 0.0
        startElapsed = SystemClock.elapsedRealtime()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!running) return
            val previous = lastLocation
            if (previous != null) {
                val results = FloatArray(1)
                Location.distanceBetween(
                    previous.latitude, previous.longitude,
                    location.latitude, location.longitude,
                    results
                )
                val segment = results[0].toDouble()
                // Ignore jitter when standing still.
                if (segment >= MIN_SEGMENT_M) {
                    distanceMeters += segment
                }
            }
            lastLocation = location
            val speed = if (location.hasSpeed()) location.speed else computeSpeed(location, previous)
            emit(speed)
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {
            listener.onLocationStatus("GPS turned off.")
            stop()
        }
    }

    private fun computeSpeed(current: Location, previous: Location?): Float {
        if (previous == null) return 0f
        val dt = (current.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
        if (dt <= 0.0) return 0f
        val results = FloatArray(1)
        Location.distanceBetween(
            previous.latitude, previous.longitude,
            current.latitude, current.longitude,
            results
        )
        return (results[0] / dt).toFloat()
    }

    private fun emit(speedMps: Float) {
        val elapsed = if (running) SystemClock.elapsedRealtime() - startElapsed else 0L
        val elapsedSec = elapsed / 1000.0
        val avg = if (elapsedSec > 0.0) distanceMeters / elapsedSec else 0.0
        handler.post {
            listener.onMetrics(
                distanceMeters,
                max(0f, speedMps),
                avg,
                elapsed
            )
        }
    }

    companion object {
        private const val MIN_TIME_MS = 1000L
        private const val MIN_DISTANCE_M = 0f
        private const val MIN_SEGMENT_M = 0.5
    }
}
