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

    @Test
    fun idleMobileCountersKeepBaselineButFailedReadsAreNotFreshSamples() {
        val m = MetricsState(1_000)
        val attrs = listOf("activity.network.scope" to "device")
        m.addCumulative(MetricNames.MOBILE_DATA_USAGE, attrs, 0, 500, 1_000)
        val restored = MetricsState.fromJson(m.toJson())
        val idle = restored.export(48 * hour).single { it.name == MetricNames.MOBILE_DATA_USAGE }
        assertEquals(0.0, idle.points.single().value, 0.0)
        assertEquals(500_000_000L, idle.points.single().startTimeUnixNano)
        restored.addCumulative(MetricNames.MOBILE_DATA_USAGE, attrs, 25, 48 * hour, 48 * hour)
        val exported = restored.export(48 * hour).single { it.name == MetricNames.MOBILE_DATA_USAGE }
        assertEquals(25.0, exported.points.single().value, 0.0)
        assertEquals(500_000_000L, exported.points.single().startTimeUnixNano)
        val failed = restored.export(49 * hour, mobileNetworkObserved = false)
        assertTrue(failed.none { it.name == MetricNames.MOBILE_DATA_USAGE })
        assertTrue(failed.any { it.name == MetricNames.ACTIVITY_TIME })
        assertEquals(25.0, restored.value(MetricNames.MOBILE_DATA_USAGE, attrs), 0.0)
    }
}
