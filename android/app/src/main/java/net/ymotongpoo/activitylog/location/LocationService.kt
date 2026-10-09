package net.ymotongpoo.activitylog.location

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import net.ymotongpoo.activitylog.R
import net.ymotongpoo.activitylog.settings.Permissions
import net.ymotongpoo.activitylog.settings.SettingsStore
import net.ymotongpoo.activitylog.ui.MainActivity

/**
 * Foreground service that requests a location every minute. Android limits
 * background apps to a few location updates per hour, so frequent fixes need a
 * foreground service of type "location" with a visible notification.
 */
class LocationService : Service() {
    private var manager: LocationManager? = null
    private val listener = LocationListener { location ->
        LocationQueue(filesDir).append(LocationCollector.toFix(location, SOURCE_FOREGROUND))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = SettingsStore(this).load()
        if (!settings.locationEnabled || settings.locationInterval != LocationInterval.MINUTE ||
            !Permissions.hasBackgroundLocation(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        requestUpdates(settings.locationInterval.millis)
        running = true
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(intervalMs: Long) {
        val lm = getSystemService(LocationManager::class.java) ?: return
        manager?.removeUpdates(listener)
        manager = lm
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val provider = if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
                    LocationManager.FUSED_PROVIDER
                } else {
                    LocationManager.GPS_PROVIDER
                }
                val request = LocationRequest.Builder(intervalMs)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(intervalMs)
                    .build()
                lm.requestLocationUpdates(provider, request, mainExecutor, listener)
            } else {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "location updates refused", e)
            stopSelf()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "location provider unavailable", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        manager?.removeUpdates(listener)
        running = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.location_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.location_notification_title))
            .setContentText(getString(R.string.location_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "activitylog"
        private const val CHANNEL_ID = "location"
        private const val NOTIFICATION_ID = 1
        const val SOURCE_FOREGROUND = "foreground"

        /** Whether the service runs in this process; the job skips its own fix then. */
        @Volatile
        var running = false
            private set

        /**
         * Starts or stops the service to match the settings. Starting is only
         * allowed from the foreground, after boot or after an update; elsewhere
         * the attempt is ignored and the next allowed caller starts it.
         */
        fun sync(context: Context) {
            val ctx = context.applicationContext
            val settings = SettingsStore(ctx).load()
            val wanted = settings.locationEnabled && settings.locationInterval == LocationInterval.MINUTE &&
                Permissions.hasBackgroundLocation(ctx)
            val intent = Intent(ctx, LocationService::class.java)
            if (!wanted) {
                ctx.stopService(intent)
                return
            }
            if (running) return
            try {
                ctx.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException on Android 12+ when started from the background.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Log.i(TAG, "location service not started from the background")
                } else {
                    throw e
                }
            }
        }
    }
}
