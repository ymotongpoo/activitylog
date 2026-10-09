package net.ymotongpoo.activitylog.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import net.ymotongpoo.activitylog.settings.Permissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads the device location with the platform LocationManager (no Play services):
 * a one-shot fix from the collection job, plus passive updates delivered to
 * [LocationReceiver] whenever another app requests a location.
 */
class LocationCollector(context: Context) {
    private val ctx = context.applicationContext
    private val manager: LocationManager? = ctx.getSystemService(LocationManager::class.java)

    /** Background location is required: fixes are taken by the periodic job. */
    fun permitted(): Boolean = Permissions.hasBackgroundLocation(ctx)

    /** (Re)registers passive updates. Registrations do not survive reboots, so this runs on every collection. */
    @SuppressLint("MissingPermission")
    fun registerPassive() {
        val lm = manager ?: return
        if (!permitted()) return
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, PASSIVE_MIN_TIME_MS, PASSIVE_MIN_DISTANCE_M, pendingIntent())
        } catch (e: SecurityException) {
            Log.w(TAG, "passive location updates refused", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "passive provider unavailable", e)
        }
    }

    fun unregisterPassive() {
        manager?.removeUpdates(pendingIntent())
    }

    /** Requests a current fix and waits up to [timeoutMs]; null when none is available. */
    @SuppressLint("MissingPermission")
    fun currentFix(timeoutMs: Long): LocationFix? {
        val lm = manager ?: return null
        if (!permitted()) return null
        return try {
            val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = bestProvider(lm) ?: return null
                val latch = CountDownLatch(1)
                var result: Location? = null
                val cancel = CancellationSignal()
                val executor = Executors.newSingleThreadExecutor()
                try {
                    lm.getCurrentLocation(provider, cancel, executor) { result = it; latch.countDown() }
                    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) cancel.cancel()
                } finally {
                    executor.shutdown()
                }
                result
            } else {
                lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            }
            location?.let { toFix(it, SOURCE_PERIODIC) }
        } catch (e: SecurityException) {
            Log.w(TAG, "location refused", e)
            null
        }
    }

    private fun bestProvider(lm: LocationManager): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            lm.hasProvider(LocationManager.FUSED_PROVIDER) && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)
        ) {
            return LocationManager.FUSED_PROVIDER
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { lm.isProviderEnabled(it) }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        0,
        Intent(ctx, LocationReceiver::class.java).setAction(LocationReceiver.ACTION),
        // Mutable: the platform adds the locations as extras.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    companion object {
        private const val TAG = "activitylog"
        const val SOURCE_PERIODIC = "periodic"
        const val SOURCE_PASSIVE = "passive"
        private const val PASSIVE_MIN_TIME_MS = 60_000L
        private const val PASSIVE_MIN_DISTANCE_M = 25f

        fun toFix(l: Location, source: String) = LocationFix(
            timeMs = l.time,
            lat = l.latitude,
            lon = l.longitude,
            accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
            altitudeM = if (l.hasAltitude()) l.altitude else null,
            speedMps = if (l.hasSpeed()) l.speed.toDouble() else null,
            bearingDeg = if (l.hasBearing()) l.bearing.toDouble() else null,
            provider = l.provider.orEmpty(),
            source = source,
            mock = isMock(l),
        )

        @Suppress("DEPRECATION")
        private fun isMock(l: Location): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l.isMock else l.isFromMockProvider
    }
}
