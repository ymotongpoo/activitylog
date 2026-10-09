package net.ymotongpoo.activitylog.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.ymotongpoo.activitylog.location.LocationService

/**
 * Runs a collection right after boot (WorkManager itself reschedules the periodic job)
 * and restarts the location service after boot and app updates, which are among the
 * few moments a foreground service may be started from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Scheduler.ensurePeriodic(context)
                Scheduler.runNow(context)
                LocationService.sync(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> LocationService.sync(context)
        }
    }
}
