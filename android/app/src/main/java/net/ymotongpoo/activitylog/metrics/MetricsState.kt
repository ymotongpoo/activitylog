package net.ymotongpoo.activitylog.metrics

import net.ymotongpoo.activitylog.otlp.SumMetric
import net.ymotongpoo.activitylog.otlp.SumPoint
import net.ymotongpoo.activitylog.otlp.attr
import net.ymotongpoo.activitylog.otlp.millisToNanos
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent cumulative totals. The start time is fixed when the state is created so
 * the exported cumulative sums keep increasing across process deaths.
 */
class MetricsState(
    val startTimeMs: Long,
    private val series: MutableMap<String, Series> = linkedMapOf(),
) {
    data class Series(
        val name: String,
        val attributes: List<Pair<String, String>>,
        var value: Double,
        var lastUpdatedMs: Long,
    )

    fun add(name: String, attributes: List<Pair<String, String>>, delta: Double, atMs: Long) {
        if (delta <= 0.0) return
        val s = series.getOrPut(key(name, attributes)) { Series(name, attributes, 0.0, atMs) }
        s.value += delta
        s.lastUpdatedMs = maxOf(s.lastUpdatedMs, atMs)
    }

    fun value(name: String, attributes: List<Pair<String, String>> = emptyList()): Double =
        series[key(name, attributes)]?.value ?: 0.0

    fun allSeries(): List<Series> = series.values.toList()

    /**
     * Builds the metrics to export at [nowMs]. Series that have not changed within
     * [activeWindowMs] are omitted so stale apps/domains do not stay active forever;
     * the device-level series are always present.
     */
    fun export(nowMs: Long, activeWindowMs: Long = DEFAULT_ACTIVE_WINDOW_MS): List<SumMetric> {
        for ((name, attrs) in ALWAYS_EXPORTED) {
            series.getOrPut(key(name, attrs)) { Series(name, attrs, 0.0, nowMs) }
        }
        val alwaysKeys = ALWAYS_EXPORTED.map { key(it.first, it.second) }.toSet()
        val startNs = startTimeMs.millisToNanos()
        val nowNs = maxOf(nowMs, startTimeMs).millisToNanos()
        val byName = series.entries
            .filter { (k, s) -> k in alwaysKeys || nowMs - s.lastUpdatedMs <= activeWindowMs }
            .map { it.value }
            .groupBy { it.name }
        return MetricNames.info.mapNotNull { (name, info) ->
            val points = byName[name] ?: return@mapNotNull null
            SumMetric(
                name = name,
                description = info.description,
                unit = info.unit,
                integer = info.integer,
                points = points.map { s ->
                    SumPoint(s.attributes.map { attr(it.first, it.second) }, startNs, nowNs, s.value)
                },
            )
        }
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (s in series.values) {
            val attrs = JSONArray()
            for ((k, v) in s.attributes) attrs.put(JSONArray().put(k).put(v))
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("attributes", attrs)
                    .put("value", s.value)
                    .put("lastUpdatedMs", s.lastUpdatedMs),
            )
        }
        return JSONObject().put("startTimeMs", startTimeMs).put("series", arr)
    }

    companion object {
        const val DEFAULT_ACTIVE_WINDOW_MS = 24L * 60 * 60 * 1000

        val ACTIVE_ATTRS = listOf("activity.state" to "active")

        private val ALWAYS_EXPORTED = listOf(
            MetricNames.ACTIVITY_TIME to ACTIVE_ATTRS,
            MetricNames.DEVICE_UNLOCKS to emptyList(),
        )

        fun key(name: String, attributes: List<Pair<String, String>>): String =
            buildString {
                append(name)
                for ((k, v) in attributes.sortedBy { it.first }) {
                    append('\u0000').append(k).append('=').append(v)
                }
            }

        fun fromJson(o: JSONObject): MetricsState {
            val state = MetricsState(o.getLong("startTimeMs"))
            val arr = o.optJSONArray("series") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val attrsJson = s.getJSONArray("attributes")
                val attrs = (0 until attrsJson.length()).map {
                    val pair = attrsJson.getJSONArray(it)
                    pair.getString(0) to pair.getString(1)
                }
                val name = s.getString("name")
                state.series[key(name, attrs)] =
                    Series(name, attrs, s.getDouble("value"), s.getLong("lastUpdatedMs"))
            }
            return state
        }
    }
}
