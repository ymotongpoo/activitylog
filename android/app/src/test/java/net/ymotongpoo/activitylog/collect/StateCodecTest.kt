package net.ymotongpoo.activitylog.collect

import net.ymotongpoo.activitylog.metrics.MetricNames
import net.ymotongpoo.activitylog.metrics.MetricsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateCodecTest {
    @Test
    fun roundTrip() {
        val tracker = TrackerState(
            checkpointMs = 100,
            interactive = true,
            locked = null,
            keyguardSeen = true,
            lastScreenOnMs = 50,
            foregroundPackage = "com.example.a",
            foregroundResumedMs = 60,
            root = OpenRoot("a".repeat(32), "b".repeat(16), 10, 90, 3, "c".repeat(32), "d".repeat(16)),
            app = OpenApp("e".repeat(16), "com.example.a", 20, 90),
            tab = OpenTab("f".repeat(16), "https://example.com/", 30, 90),
        )
        tracker.pending["com.android.chrome"] = PendingObservation(95, "example.org")
        val metrics = MetricsState(5)
        metrics.add(MetricNames.APP_TIME, listOf("activity.app.name" to "A", "activity.app.id" to "com.example.a"), 1.5, 90)
        val agent = AgentState("0.1.0", 7, true, false)

        val decoded = StateCodec.decode(StateCodec.encode(PersistedState(tracker, metrics, agent)))
        assertEquals(tracker, decoded.tracker)
        assertNull(decoded.tracker.locked)
        assertEquals(agent, decoded.agent)
        assertEquals(5, decoded.metrics.startTimeMs)
        assertEquals(metrics.allSeries(), decoded.metrics.allSeries())
    }

    @Test
    fun initialStateLooksBack() {
        val s = PersistedState.initial(10_000_000, keyguardSecure = true)
        assertEquals(10_000_000 - PersistedState.FIRST_RUN_LOOKBACK_MS, s.tracker.checkpointMs)
        assertEquals(s.tracker.checkpointMs, s.metrics.startTimeMs)
        assertEquals(true, s.tracker.keyguardSeen)
    }
}
