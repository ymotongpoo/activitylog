package net.ymotongpoo.activitylog.sleep

import android.content.Context
import java.io.IOException

/** Private, backup-excluded sleep history. Export checkpoints follow durable OTLP spooling. */
class SleepStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sleep_health_connect", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) { "Could not save sleep setting" } }
    val status: String get() = prefs.getString("status", "Sleep import is off").orEmpty()
    val lastSyncMs: Long get() = prefs.getLong("lastSyncMs", 0)
    fun sessions(): Map<String, SleepSession> = SleepCodec.decodeState(prefs.getString("sessions", "[]").orEmpty())
    fun status(value: String) { prefs.edit().putString("status", value).apply() }

    fun checkpoint(sessions: Map<String, SleepSession>, syncedMs: Long, changes: Int) {
        if (!prefs.edit().putString("sessions", SleepCodec.encodeState(sessions)).putLong("lastSyncMs", syncedMs)
                .putString("status", "${sessions.size} sleep session(s); $changes new, changed or deleted record(s)").commit()
        ) throw IOException("Could not checkpoint sleep import")
    }
}
