package net.ymotongpoo.activitylog.garmin

import net.ymotongpoo.activitylog.otlp.OtlpJsonEncoder
import net.ymotongpoo.activitylog.otlp.Scope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GarminTelemetryTest {
    private fun receipt(captured: Long, battery: GarminProtocol.Value?, stress: GarminProtocol.Value?, device: Long = 7) =
        GarminProtocol.Receipt(device, "Enduro 3", captured + 10, GarminProtocol.Sample(captured, battery, stress))

    @Test fun measurementsKeepTheirTimestampsAndMissingValuesStayMissing() {
        val batch = GarminTelemetry.build(listOf(receipt(1000, GarminProtocol.Value(33, 900), null)), emptyMap())
        val json = JSONObject(OtlpJsonEncoder.encodeGauges(emptyList(), Scope("test", "1"), batch.metrics))
        val metrics = json.getJSONArray("resourceMetrics").getJSONObject(0)
            .getJSONArray("scopeMetrics").getJSONObject(0).getJSONArray("metrics")
        assertEquals(1, metrics.length())
        val metric = metrics.getJSONObject(0)
        assertEquals("garmin.body_battery", metric.getString("name"))
        assertFalse(metric.has("sum"))
        val point = metric.getJSONObject("gauge").getJSONArray("dataPoints").getJSONObject(0)
        assertEquals("900000000000", point.getString("timeUnixNano"))
        assertEquals("33", point.getString("asInt"))
        assertFalse(point.has("startTimeUnixNano"))
    }

    @Test fun repeatedReadingsAndReplayedBatchesAreNotExportedAgain() {
        val first = receipt(1000, GarminProtocol.Value(33, 900), GarminProtocol.Value(0, 950))
        val repeated = receipt(1300, GarminProtocol.Value(33, 900), GarminProtocol.Value(21, 1250))
        val batch = GarminTelemetry.build(listOf(repeated, first, first), emptyMap())
        assertEquals(listOf(1, 2), batch.metrics.map { it.points.size })
        assertEquals(listOf(0L, 21L), batch.metrics.last().points.map { it.value })
        assertTrue(GarminTelemetry.build(listOf(first, repeated), batch.checkpoints).metrics.isEmpty())
        val next = receipt(1600, GarminProtocol.Value(32, 1500), GarminProtocol.Value(21, 1250))
        assertEquals(listOf("garmin.body_battery"), GarminTelemetry.build(listOf(next), batch.checkpoints).metrics.map { it.name })
    }

    @Test fun devicesAndMetricsHaveIndependentCheckpoints() {
        val row = receipt(1000, GarminProtocol.Value(33, 900), GarminProtocol.Value(10, 950))
        val previous = GarminTelemetry.build(listOf(row), emptyMap()).checkpoints
        val other = receipt(1000, GarminProtocol.Value(50, 900), GarminProtocol.Value(20, 950), device = 8)
        val batch = GarminTelemetry.build(listOf(row, other), previous)
        assertEquals(listOf(50L, 20L), batch.metrics.map { it.points.single().value })
        assertEquals(4, batch.checkpoints.size)
        assertTrue(GarminTelemetry.build(listOf(receipt(1200, null, null)), batch.checkpoints).metrics.isEmpty())
    }

    @Test fun decimalsAndDailyStepsResetExportAsGaugeLevels() {
        val first = receipt(1000, null, null).copy(sample = GarminProtocol.Sample(1000, null, null, mapOf(
            "watchBattery" to GarminProtocol.Value(89.125, 1000),
            "heartRate" to GarminProtocol.Value(138.5, 990),
            "stepsToday" to GarminProtocol.Value(12345, 1000),
        )))
        val midnight = first.copy(sample = GarminProtocol.Sample(1300, null, null, mapOf(
            "watchBattery" to GarminProtocol.Value(89.0625, 1300),
            "stepsToday" to GarminProtocol.Value(0, 1300),
        )))
        val batch = GarminTelemetry.build(listOf(first, midnight), emptyMap())
        val steps = batch.metrics.single { it.name == "garmin.steps_today" }
        assertEquals(listOf(12345L, 0L), steps.points.map { it.value })
        assertEquals("{step}", steps.unit)
        val json = JSONObject(OtlpJsonEncoder.encodeGauges(emptyList(), Scope("test", "1"), batch.metrics))
        val metrics = json.getJSONArray("resourceMetrics").getJSONObject(0)
            .getJSONArray("scopeMetrics").getJSONObject(0).getJSONArray("metrics")
        val battery = (0 until metrics.length()).map { metrics.getJSONObject(it) }
            .single { it.getString("name") == "garmin.watch_battery" }
        val point = battery.getJSONObject("gauge").getJSONArray("dataPoints").getJSONObject(0)
        assertEquals(89.125, point.getDouble("asDouble"), 0.0)
        assertFalse(point.has("asInt"))
        assertEquals("%", battery.getString("unit"))
    }
}
