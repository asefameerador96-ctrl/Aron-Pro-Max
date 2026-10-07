package com.aktcl.aron.core.geo

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.aktcl.aron.core.common.WallClock

/**
 * GNSS evidence for one fix window (N-025, docs/24 s5.5): GnssStatus and, where the phone supports them, raw
 * measurements are registered when the provider request starts and unregistered when it ends. Nothing runs between
 * fixes. With the balanced-power provider the GNSS chip is often not used, and then the summary honestly says zero
 * satellites.
 */
class AndroidGnssObserver(context: Context, private val clock: WallClock) : FixWindowObserver {
    private val app = context.applicationContext
    private val lm = app.getSystemService(LocationManager::class.java)
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Raw-measurement capability of this phone model: API 31+ reports it; below, it is unknown (null) until the window
     * shows it (a measurement arrives, or the callback reports STATUS_NOT_SUPPORTED, or registration fails).
     */
    val rawSupported: Boolean? by lazy {
        if (Build.VERSION.SDK_INT >= 31) runCatching { lm?.gnssCapabilities?.hasMeasurements() == true }.getOrDefault(false) else null
    }

    @SuppressLint("MissingPermission") // FixManager checked the permission; a SecurityException yields no window data.
    override fun open(): FixWindow {
        val manager = lm ?: return FixWindow { null }
        val acc = GnssAccumulator(clock.elapsedRealtimeMs(), rawSupported)
        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) {
                acc.onStatus((0 until s.satelliteCount).map { i ->
                    SatelliteSample(s.getConstellationType(i), s.getCn0DbHz(i).toDouble(), s.usedInFix(i), s.hasEphemerisData(i))
                })
            }
        }
        val measurements = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(e: GnssMeasurementsEvent) {
                val agc = if (Build.VERSION.SDK_INT >= 33) {
                    e.gnssAutomaticGainControls.map { it.levelDb }
                } else {
                    @Suppress("DEPRECATION")
                    e.measurements.filter { it.hasAutomaticGainControlLevelDb() }.map { it.automaticGainControlLevelDb }
                }
                acc.onMeasurements(e.measurements.size, agc)
            }

            @Deprecated("Deprecated in API 31")
            override fun onStatusChanged(status: Int) {
                when (status) {
                    STATUS_NOT_SUPPORTED -> acc.markRawSupported(false)
                    STATUS_READY -> acc.markRawSupported(true)
                }
            }
        }
        val statusOn = runCatching { manager.registerGnssStatusCallback(status, handler) }.getOrDefault(false)
        val measOn = rawSupported != false && runCatching { manager.registerGnssMeasurementsCallback(measurements, handler) }.getOrDefault(false)
        if (!measOn) acc.markRawSupported(false)
        return FixWindow {
            if (statusOn) runCatching { manager.unregisterGnssStatusCallback(status) }
            if (measOn) runCatching { manager.unregisterGnssMeasurementsCallback(measurements) }
            acc.summaryJson(clock.elapsedRealtimeMs())
        }
    }
}
