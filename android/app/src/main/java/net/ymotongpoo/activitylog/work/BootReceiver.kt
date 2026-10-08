package net.ymotongpoo.activitylog.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runs a collection right after boot (WorkManager itself reschedules the periodic job). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Scheduler.ensurePeriodic(context)
        Scheduler.runNow(context)
    }
}
