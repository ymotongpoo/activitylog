package net.ymotongpoo.activitylog.collect

import net.ymotongpoo.activitylog.metrics.MetricsState
import org.json.JSONObject

/** Facts about the app itself used for `agent.start` / `agent.permission` logs. */
data class AgentState(
    var lastVersion: String? = null,
    var lastBootCount: Int = -1,
    var usageAccessGranted: Boolean? = null,
    var accessibilityEnabled: Boolean? = null,
)

/**
 * Everything persisted between runs. Tracker state and metric totals live in one file
 * so they are always committed together and time is never credited twice.
 */
class PersistedState(
    val tracker: TrackerState,
    val metrics: MetricsState,
    val agent: AgentState = AgentState(),
) {
    companion object {
        /** On the first run, read this much history so the user sees data right away. */
        const val FIRST_RUN_LOOKBACK_MS = 60L * 60 * 1000

        fun initial(nowMs: Long, keyguardSecure: Boolean): PersistedState {
            val start = nowMs - FIRST_RUN_LOOKBACK_MS
            return PersistedState(
                tracker = TrackerState(checkpointMs = start, keyguardSeen = keyguardSecure),
                metrics = MetricsState(startTimeMs = start),
            )
        }
    }
}

object StateCodec {
    private const val VERSION = 1

    fun encode(state: PersistedState): String = JSONObject()
        .put("version", VERSION)
        .put("tracker", encodeTracker(state.tracker))
        .put("metrics", state.metrics.toJson())
        .put("agent", encodeAgent(state.agent))
        .toString()

    /** Throws [org.json.JSONException] when the content is corrupt. */
    fun decode(text: String): PersistedState {
        val o = JSONObject(text)
        return PersistedState(
            tracker = decodeTracker(o.getJSONObject("tracker")),
            metrics = MetricsState.fromJson(o.getJSONObject("metrics")),
            agent = o.optJSONObject("agent")?.let(::decodeAgent) ?: AgentState(),
        )
    }

    private fun encodeTracker(t: TrackerState): JSONObject {
        val o = JSONObject()
            .put("checkpointMs", t.checkpointMs)
            .put("keyguardSeen", t.keyguardSeen)
            .put("lastScreenOnMs", t.lastScreenOnMs)
            .put("foregroundResumedMs", t.foregroundResumedMs)
        t.interactive?.let { o.put("interactive", it) }
        t.locked?.let { o.put("locked", it) }
        t.foregroundPackage?.let { o.put("foregroundPackage", it) }
        t.root?.let { r ->
            val ro = JSONObject()
                .put("traceId", r.traceId)
                .put("spanId", r.spanId)
                .put("startMs", r.startMs)
                .put("creditedUntilMs", r.creditedUntilMs)
                .put("switches", r.switches)
            r.linkTraceId?.let { ro.put("linkTraceId", it) }
            r.linkSpanId?.let { ro.put("linkSpanId", it) }
            o.put("root", ro)
        }
        t.app?.let { a ->
            o.put(
                "app",
                JSONObject()
                    .put("spanId", a.spanId)
                    .put("packageName", a.packageName)
                    .put("startMs", a.startMs)
                    .put("creditedUntilMs", a.creditedUntilMs),
            )
        }
        t.tab?.let { tab ->
            o.put(
                "tab",
                JSONObject()
                    .put("spanId", tab.spanId)
                    .put("url", tab.url)
                    .put("startMs", tab.startMs)
                    .put("creditedUntilMs", tab.creditedUntilMs),
            )
        }
        val pending = JSONObject()
        for ((pkg, p) in t.pending) pending.put(pkg, JSONObject().put("timeMs", p.timeMs).put("text", p.text))
        o.put("pending", pending)
        return o
    }

    private fun decodeTracker(o: JSONObject): TrackerState {
        val t = TrackerState(
            checkpointMs = o.getLong("checkpointMs"),
            interactive = o.optBool("interactive"),
            locked = o.optBool("locked"),
            keyguardSeen = o.optBoolean("keyguardSeen", false),
            lastScreenOnMs = o.optLong("lastScreenOnMs", 0),
            foregroundPackage = o.optStr("foregroundPackage"),
            foregroundResumedMs = o.optLong("foregroundResumedMs", 0),
        )
        o.optJSONObject("root")?.let { r ->
            t.root = OpenRoot(
                traceId = r.getString("traceId"),
                spanId = r.getString("spanId"),
                startMs = r.getLong("startMs"),
                creditedUntilMs = r.getLong("creditedUntilMs"),
                switches = r.optInt("switches", 0),
                linkTraceId = r.optStr("linkTraceId"),
                linkSpanId = r.optStr("linkSpanId"),
            )
        }
        o.optJSONObject("app")?.let { a ->
            t.app = OpenApp(a.getString("spanId"), a.getString("packageName"), a.getLong("startMs"), a.getLong("creditedUntilMs"))
        }
        o.optJSONObject("tab")?.let { tab ->
            t.tab = OpenTab(tab.getString("spanId"), tab.getString("url"), tab.getLong("startMs"), tab.getLong("creditedUntilMs"))
        }
        o.optJSONObject("pending")?.let { p ->
            for (pkg in p.keys()) {
                val e = p.getJSONObject(pkg)
                t.pending[pkg] = PendingObservation(e.getLong("timeMs"), e.getString("text"))
            }
        }
        return t
    }

    private fun encodeAgent(a: AgentState): JSONObject {
        val o = JSONObject().put("lastBootCount", a.lastBootCount)
        a.lastVersion?.let { o.put("lastVersion", it) }
        a.usageAccessGranted?.let { o.put("usageAccessGranted", it) }
        a.accessibilityEnabled?.let { o.put("accessibilityEnabled", it) }
        return o
    }

    private fun decodeAgent(o: JSONObject) = AgentState(
        lastVersion = o.optStr("lastVersion"),
        lastBootCount = o.optInt("lastBootCount", -1),
        usageAccessGranted = o.optBool("usageAccessGranted"),
        accessibilityEnabled = o.optBool("accessibilityEnabled"),
    )

    // Android's org.json returns "null" from optString for JSON null; be explicit.
    private fun JSONObject.optStr(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.optBool(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
}
