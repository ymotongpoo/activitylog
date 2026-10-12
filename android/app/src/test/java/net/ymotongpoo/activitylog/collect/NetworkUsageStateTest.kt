package net.ymotongpoo.activitylog.collect

import net.ymotongpoo.activitylog.metrics.MetricNames
import net.ymotongpoo.activitylog.metrics.MetricsState
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NetworkUsageStateTest {
    private val utc = ZoneId.of("UTC")
    private val day = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val deviceAttrs = listOf("activity.network.scope" to "device")

    @Test
    fun wifiAndMobileBaselinesRemainIndependentAcrossRestartAndMidnight() {
        val state = PersistedState.initial(day + hour, false)
        state.mobileNetwork = NetworkUsageState(day, 100, mapOf(42 to 60))
        state.wifiNetwork = NetworkUsageState(day, 1_000, mapOf(42 to 600))
        val restored = StateCodec.decode(StateCodec.encode(state))
        val now = day + 25 * hour
        val mobile = NetworkUsageState.collect(restored.mobileNetwork, now, utc) { start, _ ->
            if (start == day) snapshot(130, 42 to 80) else snapshot(10, 42 to 5)
        }
        val wifi = NetworkUsageState.collect(restored.wifiNetwork, now, utc) { start, _ ->
            if (start == day) snapshot(1_400, 42 to 900) else snapshot(200, 42 to 100)
        }
        assertEquals(40L, mobile.deviceDelta)
        assertEquals(25L, mobile.uidDeltas.getValue(42))
        assertEquals(600L, wifi.deviceDelta)
        assertEquals(400L, wifi.uidDeltas.getValue(42))
        assertEquals(100L, restored.mobileNetwork!!.deviceBytes)
        assertEquals(1_000L, restored.wifiNetwork!!.deviceBytes)
    }

    private fun snapshot(device: Long, vararg uids: Pair<Int, Long>) = NetworkUsageSnapshot(
        NetworkBytes(device / 2, device - device / 2),
        uids.associate { (uid, bytes) -> uid to NetworkBytes(bytes / 2, bytes - bytes / 2) },
    )

    @Test
    fun repeatedSnapshotsAndRestartOnlyCreditNewBytes() {
        val first = NetworkUsageState.collect(null, day + hour, utc) { start, _ ->
            assertEquals(day, start)
            snapshot(100, 42 to 60)
        }
        assertEquals(100L, first.deviceDelta)
        val restarted = NetworkUsageState.fromJson(first.state.toJson())
        val second = NetworkUsageState.collect(restarted, day + 2 * hour, utc) { _, _ ->
            snapshot(130, 42 to 75)
        }
        assertEquals(30L, second.deviceDelta)
        assertEquals(15L, second.uidDeltas.getValue(42))
        val same = NetworkUsageState.collect(second.state, day + 3 * hour, utc) { _, _ ->
            snapshot(130, 42 to 75)
        }
        assertEquals(0L, same.deviceDelta)
        assertEquals(0L, same.uidDeltas.getValue(42))
    }

    @Test
    fun midnightIncludesUnobservedTailAndKeepsCounterStart() {
        val previous = NetworkUsageState(day, 100, mapOf(42 to 60))
        val calls = mutableListOf<Pair<Long, Long>>()
        val now = day + 24 * hour + hour / 4
        val update = NetworkUsageState.collect(previous, now, utc) { start, end ->
            calls += start to end
            if (start == day) snapshot(140, 42 to 90, 77 to 10)
            else snapshot(30, 42 to 20)
        }
        assertEquals(listOf(day to day + 24 * hour, day + 24 * hour to now), calls)
        assertEquals(70L, update.deviceDelta)
        assertEquals(mapOf(42 to 50L, 77 to 10L), update.uidDeltas)
        val metrics = MetricsState(day)
        metrics.addCumulative(MetricNames.MOBILE_DATA_USAGE, deviceAttrs, 100, day, day + 23 * hour)
        metrics.addCumulative(MetricNames.MOBILE_DATA_USAGE, deviceAttrs, update.deviceDelta, update.startMs, now)
        val point = metrics.export(now).single { it.name == MetricNames.MOBILE_DATA_USAGE }.points.single()
        assertEquals(170.0, point.value, 0.0)
        assertEquals(day * 1_000_000, point.startTimeUnixNano)
    }

    @Test
    fun missedDaysAreIncludedInTheFinishedWindow() {
        val previous = NetworkUsageState(day, 100, mapOf(42 to 60))
        val nextDay = day + 3 * 24 * hour
        val update = NetworkUsageState.collect(previous, nextDay + hour, utc) { start, end ->
            if (start == day) {
                assertEquals(nextDay, end)
                snapshot(1_000, 42 to 800)
            } else snapshot(50, 42 to 30)
        }
        assertEquals(950L, update.deviceDelta)
        assertEquals(770L, update.uidDeltas.getValue(42))
    }

    @Test
    fun failedRolloverDoesNotAdvanceCheckpointAndRetryIncludesTailOnce() {
        val previous = NetworkUsageState(day, 100, mapOf(42 to 60))
        val saved = previous.toJson().toString()
        assertThrows(IllegalStateException::class.java) {
            NetworkUsageState.collect(previous, day + 25 * hour, utc) { start, _ ->
                if (start == day) snapshot(140, 42 to 90) else error("query failed")
            }
        }
        assertEquals(saved, previous.toJson().toString())
        val retry = NetworkUsageState.collect(previous, day + 26 * hour, utc) { start, _ ->
            if (start == day) snapshot(140, 42 to 90) else snapshot(50, 42 to 40)
        }
        assertEquals(90L, retry.deviceDelta)
        assertEquals(70L, retry.uidDeltas.getValue(42))
    }

    @Test
    fun smallerOrMissingSourceTotalsDoNotDoubleCountOnRecovery() {
        val previous = NetworkUsageState(day, 100, mapOf(42 to 60))
        val smaller = NetworkUsageState.collect(previous, day + hour, utc) { _, _ -> snapshot(80) }
        assertEquals(0L, smaller.deviceDelta)
        assertEquals(previous, smaller.state)
        val recovered = NetworkUsageState.collect(smaller.state, day + 2 * hour, utc) { _, _ ->
            snapshot(110, 42 to 65)
        }
        assertEquals(10L, recovered.deviceDelta)
        assertEquals(5L, recovered.uidDeltas.getValue(42))
    }

    @Test
    fun legacyDailyStateIsNotCountedAgainAfterUpgrade() {
        val metrics = MetricsState(day)
        metrics.addCumulative(MetricNames.MOBILE_DATA_USAGE, deviceAttrs, 100, day, day + hour)
        metrics.addCumulative(
            MetricNames.MOBILE_DATA_USAGE,
            listOf("activity.network.scope" to "app", "process.uid" to "42"),
            60, day, day + hour,
        )
        val previous = NetworkUsageState.fromLegacyMobile(MetricsState.fromJson(metrics.toJson()))
        val updated = NetworkUsageState.collect(previous, day + 2 * hour, utc) { _, _ ->
            snapshot(130, 42 to 75)
        }
        assertEquals(30L, updated.deviceDelta)
        assertEquals(15L, updated.uidDeltas.getValue(42))
    }

    @Test
    fun renamedAppUsesUidBaselineInsteadOfAddingTheDailyTotalAgain() {
        val previous = NetworkUsageState(day, 100, mapOf(42 to 60))
        val metrics = MetricsState(day)
        fun attrs(name: String) = listOf(
            "activity.network.scope" to "app", "process.uid" to "42", "activity.app.name" to name,
        )
        metrics.addCumulative(MetricNames.MOBILE_DATA_USAGE, attrs("Old name"), 60, day, day + hour)
        val update = NetworkUsageState.collect(previous, day + 2 * hour, utc) { _, _ -> snapshot(130, 42 to 75) }
        metrics.addCumulative(
            MetricNames.MOBILE_DATA_USAGE, attrs("New name"), update.uidDeltas.getValue(42), day, day + 2 * hour,
        )
        assertEquals(75.0, metrics.allSeries().sumOf { it.value }, 0.0)
    }

    @Test
    fun westwardTimezoneChangeDoesNotRecountOverlappingHistory() {
        val previous = NetworkUsageState(day, 100, emptyMap())
        val updated = NetworkUsageState.collect(previous, day + hour, ZoneId.of("Pacific/Honolulu")) { start, _ ->
            assertEquals(day, start)
            snapshot(120)
        }
        assertEquals(20L, updated.deviceDelta)
    }

    @Test
    fun dstRolloverUsesCalendarMidnightInsteadOfFixed24Hours() {
        val start = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-02T05:00:00Z").toEpochMilli()
        val previous = NetworkUsageState(start, 100, emptyMap())
        NetworkUsageState.collect(previous, end + hour, ZoneId.of("America/New_York")) { from, to ->
            if (from == start) assertEquals(25 * hour, to - from)
            else assertEquals(end, from)
            snapshot(100)
        }
    }
}
