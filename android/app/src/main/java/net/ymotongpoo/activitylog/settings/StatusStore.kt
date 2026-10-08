package net.ymotongpoo.activitylog.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import net.ymotongpoo.activitylog.otlp.Severity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Diagnostic(val timeMs: Long, val severity: Severity, val message: String)

/**
 * Run/send status and internal diagnostics shown in the settings screen. Diagnostics
 * are also queued so the next run exports them as `agent.diagnostic` logs.
 */
class StatusStore(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val lastRunMs: Long get() = prefs.getLong(KEY_LAST_RUN_MS, 0)

    val lastRunSummary: String get() = prefs.getString(KEY_LAST_RUN_SUMMARY, null).orEmpty()
    val lastSendMs: Long get() = prefs.getLong(KEY_LAST_SEND_MS, 0)
    val lastSendResult: String get() = prefs.getString(KEY_LAST_SEND_RESULT, null).orEmpty()

    fun recordRun(timeMs: Long, summary: String) {
        prefs.edit {
            putLong(KEY_LAST_RUN_MS, timeMs)
            putString(KEY_LAST_RUN_SUMMARY, summary)
        }
    }

    fun recordSend(timeMs: Long, result: String) {
        prefs.edit {
            putLong(KEY_LAST_SEND_MS, timeMs)
            putString(KEY_LAST_SEND_RESULT, result)
        }
    }

    @Synchronized
    fun diagnostic(severity: Severity, message: String, timeMs: Long = System.currentTimeMillis()) {
        val d = Diagnostic(timeMs, severity, message.take(MAX_MESSAGE))
        val recent = (listOf(d) + read(KEY_RECENT)).take(MAX_RECENT)
        val pending = (read(KEY_PENDING) + d).takeLast(MAX_PENDING)
        prefs.edit {
            putString(KEY_RECENT, write(recent))
            putString(KEY_PENDING, write(pending))
        }
    }

    /** Most recent first. */
    fun recentDiagnostics(): List<Diagnostic> = read(KEY_RECENT)

    @Synchronized
    fun pendingDiagnostics(): List<Diagnostic> = read(KEY_PENDING)

    /** Removes diagnostics that have been exported. */
    @Synchronized
    fun removePending(exported: List<Diagnostic>) {
        val remaining = read(KEY_PENDING).filterNot { it in exported }
        prefs.edit { putString(KEY_PENDING, write(remaining)) }
    }

    private fun read(key: String): List<Diagnostic> {
        val text = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Diagnostic(
                    o.getLong("t"),
                    Severity.entries.firstOrNull { s -> s.name == o.getString("s") } ?: Severity.WARN,
                    o.getString("m"),
                )
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun write(list: List<Diagnostic>): String {
        val arr = JSONArray()
        for (d in list) arr.put(JSONObject().put("t", d.timeMs).put("s", d.severity.name).put("m", d.message))
        return arr.toString()
    }

    private companion object {
        const val PREFS = "status"
        const val KEY_LAST_RUN_MS = "last_run_ms"
        const val KEY_LAST_RUN_SUMMARY = "last_run_summary"
        const val KEY_LAST_SEND_MS = "last_send_ms"
        const val KEY_LAST_SEND_RESULT = "last_send_result"
        const val KEY_RECENT = "diagnostics_recent"
        const val KEY_PENDING = "diagnostics_pending"
        const val MAX_RECENT = 20
        const val MAX_PENDING = 100
        const val MAX_MESSAGE = 1000
    }
}
