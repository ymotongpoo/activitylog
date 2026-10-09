package net.ymotongpoo.activitylog.location

import net.ymotongpoo.activitylog.otlp.AttributeValue
import net.ymotongpoo.activitylog.otlp.LogData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocationLogsTest {
    private fun LogData.value(key: String): AttributeValue? = attributes.firstOrNull { it.key == key }?.value

    private val fix = LocationFix(
        timeMs = 1_791_500_000_000, lat = 35.681236, lon = 139.767125, accuracyM = 12.5,
        provider = "fused", source = "periodic",
    )

    @Test
    fun fullPrecisionKeepsCoordinates() {
        val logs = LocationLogs.build(listOf(fix), LocationPrecision.FULL, 1_791_500_100_000)
        assertEquals(1, logs.size)
        val log = logs[0]
        assertEquals(AttributeValue.Str("device.location"), log.value("event.name"))
        assertEquals(AttributeValue.DoubleVal(35.681236), log.value("geo.location.lat"))
        assertEquals(AttributeValue.DoubleVal(139.767125), log.value("geo.location.lon"))
        assertEquals(AttributeValue.DoubleVal(12.5), log.value("activity.location.accuracy"))
        assertEquals(fix.timeMs * 1_000_000, log.timeUnixNano)
        assertNull(log.value("activity.location.mock"))
        assertTrue(log.body, log.body.startsWith("Location 35.681236,139.767125"))
    }

    @Test
    fun roundsCoordinates() {
        val log = LocationLogs.build(listOf(fix), LocationPrecision.M100, 0)[0]
        assertEquals(AttributeValue.DoubleVal(35.681), log.value("geo.location.lat"))
        assertEquals(AttributeValue.DoubleVal(139.767), log.value("geo.location.lon"))
        val coarse = LocationLogs.build(listOf(fix), LocationPrecision.KM1, 0)[0]
        assertEquals(AttributeValue.DoubleVal(35.68), coarse.value("geo.location.lat"))
    }

    @Test
    fun dropsDuplicatesFromPassiveAndPeriodic() {
        val passive = fix.copy(timeMs = fix.timeMs + 200, source = "passive")
        val later = fix.copy(timeMs = fix.timeMs + 60_000)
        val logs = LocationLogs.build(listOf(later, passive, fix), LocationPrecision.FULL, 0)
        assertEquals(listOf(fix.timeMs, later.timeMs), logs.map { it.timeUnixNano / 1_000_000 })
    }

    @Test
    fun queueRoundTrip() {
        val dir: File = Files.createTempDirectory("loc").toFile()
        val queue = LocationQueue(dir)
        val full = fix.copy(altitudeM = 40.0, speedMps = 1.5, bearingDeg = 90.0, mock = true)
        queue.append(full)
        queue.append(fix.copy(accuracyM = null, provider = ""))
        val drained = queue.beginDrain()
        assertEquals(listOf(full, fix.copy(accuracyM = null, provider = "")), drained)
        queue.commitDrain()
        assertEquals(emptyList<LocationFix>(), queue.beginDrain())
    }
}
