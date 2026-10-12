package net.ymotongpoo.activitylog.collect

import net.ymotongpoo.activitylog.metrics.MetricNames
import net.ymotongpoo.activitylog.metrics.MetricsState
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Android's last observed totals, separate from the counters sent to OTLP. */
data class MobileNetworkUsageState(
    val dayStartMs: Long,
    val deviceBytes: Long,
    val uidBytes: Map<Int, Long>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("dayStartMs", dayStartMs)
        .put("deviceBytes", deviceBytes)
        .put("uidBytes", JSONObject().also { o -> uidBytes.forEach { (uid, bytes) -> o.put(uid.toString(), bytes) } })

    companion object {
        fun fromJson(o: JSONObject): MobileNetworkUsageState {
            val uids = o.getJSONObject("uidBytes")
            return MobileNetworkUsageState(
                o.getLong("dayStartMs"), o.getLong("deviceBytes"),
                uids.keys().asSequence().associate { it.toInt() to uids.getLong(it) },
            )
        }

        /** v0.6.4 saved daily totals directly in the metric series. */
        fun fromLegacy(metrics: MetricsState): MobileNetworkUsageState? {
            val series = metrics.allSeries().filter { it.name == MetricNames.MOBILE_DATA_USAGE }
            val device = series.firstOrNull { it.attributes.contains("activity.network.scope" to "device") }
                ?: return null
            val start = device.startTimeMs ?: return null
            val uids = mutableMapOf<Int, Long>()
            for (s in series.filter { it.startTimeMs == start }) {
                val uid = s.attributes.toMap()["process.uid"]?.toIntOrNull() ?: continue
                uids[uid] = maxOf(uids[uid] ?: 0, s.value.toLong())
            }
            return MobileNetworkUsageState(start, device.value.toLong(), uids)
        }

        /**
         * Query everything before changing persisted state. On a day change, finish
         * the old window (including days missed while the worker wasn't running).
         * Only positive changes from the last Android snapshot become counter deltas.
         */
        fun collect(
            previous: MobileNetworkUsageState?,
            nowMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            query: (Long, Long) -> MobileNetworkUsageSnapshot,
        ): MobileNetworkUsageUpdate {
            val midnight = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli()
            // Moving west must not query a window overlapping already credited data.
            val start = maxOf(midnight, previous?.dayStartMs ?: midnight)
            require(start <= nowMs) { "Clock moved before the last mobile usage window" }
            val rolled = previous != null && previous.dayStartMs < start
            val finished = if (rolled) query(previous!!.dayStartMs, start) else null
            val current = query(start, nowMs)
            val baselineDevice = if (rolled) 0L else previous?.deviceBytes ?: 0L
            val baselineUids = if (rolled) emptyMap() else previous?.uidBytes.orEmpty()
            val uids = previous?.uidBytes.orEmpty().keys + finished?.byUid.orEmpty().keys + current.byUid.keys
            val deltas = uids.associateWith { uid ->
                val tail = if (finished != null) {
                    positiveDelta(finished.byUid[uid]?.total ?: 0L, previous?.uidBytes?.get(uid) ?: 0L)
                } else 0L
                tail + positiveDelta(current.byUid[uid]?.total ?: 0L, baselineUids[uid] ?: 0L)
            }
            val deviceDelta = positiveDelta(current.device.total, baselineDevice) +
                (finished?.let { positiveDelta(it.device.total, previous!!.deviceBytes) } ?: 0L)
            val nextUids = (baselineUids.keys + current.byUid.keys).associateWith { uid ->
                maxOf(baselineUids[uid] ?: 0L, current.byUid[uid]?.total ?: 0L)
            }
            return MobileNetworkUsageUpdate(
                MobileNetworkUsageState(start, maxOf(baselineDevice, current.device.total), nextUids),
                previous?.dayStartMs ?: start, deviceDelta, deltas,
            )
        }

        private fun positiveDelta(value: Long, previous: Long): Long = (value - previous).coerceAtLeast(0L)
    }
}

data class MobileNetworkUsageUpdate(
    val state: MobileNetworkUsageState,
    val startMs: Long,
    val deviceDelta: Long,
    val uidDeltas: Map<Int, Long>,
)
