package net.ymotongpoo.activitylog.collect

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Reads `UsageStatsManager` events and maps them to [UsageEvent]. */
class UsageEventSource(context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    /** Events in [beginMs, endMs), in the order reported by the system. */
    fun query(beginMs: Long, endMs: Long): List<UsageEvent> {
        if (endMs <= beginMs) return emptyList()
        val events = usm?.queryEvents(beginMs, endMs) ?: return emptyList()
        val out = ArrayList<UsageEvent>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(e)) break
            val kind = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventKind.ACTIVITY_RESUMED
                UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventKind.SCREEN_INTERACTIVE
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventKind.SCREEN_NON_INTERACTIVE
                UsageEvents.Event.KEYGUARD_SHOWN -> UsageEventKind.KEYGUARD_SHOWN
                UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEventKind.KEYGUARD_HIDDEN
                UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEventKind.DEVICE_SHUTDOWN
                UsageEvents.Event.DEVICE_STARTUP -> UsageEventKind.DEVICE_STARTUP
                else -> null
            } ?: continue
            out += UsageEvent(e.timeStamp, kind, e.packageName)
        }
        return out
    }
}
