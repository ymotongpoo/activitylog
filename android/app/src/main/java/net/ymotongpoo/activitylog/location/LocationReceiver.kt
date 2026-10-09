package net.ymotongpoo.activitylog.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.IntentCompat
import net.ymotongpoo.activitylog.settings.SettingsStore

/** Receives passive location updates and queues them for the next collection. */
class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!SettingsStore(context).load().locationEnabled) {
            LocationCollector(context).unregisterPassive()
            return
        }
        val locations = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                IntentCompat.getParcelableArrayListExtra(intent, LocationManager.KEY_LOCATIONS, Location::class.java)
                    ?.let { addAll(it) }
            }
            IntentCompat.getParcelableExtra(intent, LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
                ?.let { single -> if (none { it.time == single.time }) add(single) }
        }
        val queue = LocationQueue(context.filesDir)
        for (l in locations) queue.append(LocationCollector.toFix(l, LocationCollector.SOURCE_PASSIVE))
    }

    companion object {
        const val ACTION = "net.ymotongpoo.activitylog.LOCATION_UPDATE"
    }
}
