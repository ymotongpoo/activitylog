package net.ymotongpoo.activitylog.garmin

import net.ymotongpoo.activitylog.otlp.GaugeMetric
import net.ymotongpoo.activitylog.otlp.GaugePoint
import net.ymotongpoo.activitylog.otlp.attr

/** Repeated history readings must not turn into new measurements at receipt time. */
object GarminTelemetry {
    data class Batch(val metrics: List<GaugeMetric>, val checkpoints: Map<String, Long>)

    fun build(receipts: List<GarminProtocol.Receipt>, previous: Map<String, Long>): Batch {
        val checkpoints = previous.toMutableMap()
        fun metric(spec: GarminMetric): GaugeMetric {
            val points = receipts.mapNotNull { receipt -> spec.reading(receipt.sample)?.let { receipt to it } }
                .sortedBy { it.second.time }
                .mapNotNull { (receipt, value) ->
                    val key = "${receipt.deviceId}:${spec.metricName}"
                    if (value.time <= (checkpoints[key] ?: 0)) return@mapNotNull null
                    checkpoints[key] = value.time
                    GaugePoint(
                        listOf(attr("garmin.device.id", receipt.deviceId.toString()), attr("garmin.device.name", receipt.deviceName)),
                        value.time * 1_000_000_000L,
                        if (spec.integer) value.value.toLong() else value.value.toDouble(),
                    )
                }
            return GaugeMetric(spec.metricName, spec.label, spec.unit, points)
        }
        val metrics = GarminMetric.entries.map(::metric).filter { it.points.isNotEmpty() }
        return Batch(metrics, checkpoints)
    }
}
