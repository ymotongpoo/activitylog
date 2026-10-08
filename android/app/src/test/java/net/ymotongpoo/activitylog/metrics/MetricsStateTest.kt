package net.ymotongpoo.activitylog.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsStateTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun exportKeepsStartTimeAndOmitsStaleSeries() {
        val m = MetricsState(startTimeMs = 1_000)
        m.add(MetricNames.APP_TIME, listOf("activity.app.name" to "Old", "activity.app.id" to "old"), 5.0, 2_000)
        m.add(MetricNames.APP_TIME, listOf("activity.app.name" to "New", "activity.app.id" to "new"), 7.0, 30 * hour)
        m.add(MetricNames.APP_TIME, listOf("activity.app.id" to "new", "activity.app.name" to "New"), 1.0, 30 * hour)
        m.add(MetricNames.CATEGORY_TIME, listOf("activity.category" to "x"), 0.0, 30 * hour)

        val exported = m.export(nowMs = 30 * hour + 1)
        val byName = exported.associateBy { it.name }
        val app = byName.getValue(MetricNames.APP_TIME)
        assertEquals(1, app.points.size)
        assertEquals(8.0, app.points.single().value, 0.0)
        assertEquals(1_000L * 1_000_000, app.points.single().startTimeUnixNano)
        assertEquals((30 * hour + 1) * 1_000_000, app.points.single().timeUnixNano)
        assertEquals("s", app.unit)
        // Zero deltas never create series; device-level series always exist.
        assertTrue(MetricNames.CATEGORY_TIME !in byName)
        assertEquals(0.0, byName.getValue(MetricNames.ACTIVITY_TIME).points.single().value, 0.0)
        val unlocks = byName.getValue(MetricNames.DEVICE_UNLOCKS)
        assertTrue(unlocks.integer)
        assertEquals("", unlocks.unit)
    }

    @Test
    fun jsonRoundTrip() {
        val m = MetricsState(startTimeMs = 42)
        m.add(MetricNames.BROWSER_DOMAIN_TIME, listOf("url.domain" to "example.com"), 2.5, 100)
        m.add(MetricNames.DEVICE_UNLOCKS, emptyList(), 1.0, 100)
        val r = MetricsState.fromJson(m.toJson())
        assertEquals(42L, r.startTimeMs)
        assertEquals(m.allSeries(), r.allSeries())
        assertEquals(2.5, r.value(MetricNames.BROWSER_DOMAIN_TIME, listOf("url.domain" to "example.com")), 0.0)
    }
}
