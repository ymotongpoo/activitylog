package net.ymotongpoo.activitylog

import android.app.Application
import net.ymotongpoo.activitylog.work.Scheduler

class ActivityLogApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Scheduler.ensurePeriodic(this)
    }
}
