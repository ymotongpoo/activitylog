package net.ymotongpoo.activitylog.garmin

import org.junit.Assert.*
import org.junit.Test

class GarminProtocolTest {
    private val time = 1_793_000_000L
    private fun row(at: Long = time): Map<String, Any?> = mapOf(
        "capturedAt" to at, "bodyBattery" to 73, "bodyBatteryAt" to at - 120,
        "stress" to 21L, "stressAt" to at - 60,
    )
    private fun packet(vararg rows: Map<String, Any?>): Map<String, Any> =
        mapOf("schema" to 1, "type" to "health", "samples" to rows.toList())

    @Test fun sdkWrapperPreservesSeparateTimestamps() {
        val sample = GarminProtocol.decode(listOf(packet(row()))).single()
        assertEquals(GarminProtocol.Value(73, time - 120), sample.bodyBattery)
        assertEquals(GarminProtocol.Value(21, time - 60), sample.stress)
        assertEquals(mapOf("schema" to 1, "ack" to time), GarminProtocol.acknowledgement(listOf(sample)))
    }

    @Test fun unavailableStressIsNotConvertedToZero() {
        val sample = GarminProtocol.decode(packet(row() + mapOf("stress" to null, "stressAt" to null))).single()
        assertNull(sample.stress)
        assertEquals(73, sample.bodyBattery?.value)
    }

    @Test fun addedMetricsPreserveDecimalsAndOldPacketsRemainCompatible() {
        val sample = GarminProtocol.decode(packet(row() + mapOf(
            "heartRate" to 138.5, "heartRateAt" to time - 15,
            "respirationRate" to 14.5, "respirationRateAt" to time,
            "stepsToday" to 12345, "stepsTodayAt" to time,
            "watchBattery" to 89.125, "watchBatteryAt" to time,
            "oxygenSaturation" to 98.5, "oxygenSaturationAt" to time - 300,
            "solarIntensity" to null, "solarIntensityAt" to null,
        ))).single()
        assertEquals(GarminProtocol.Value(138.5, time - 15), sample.additional["heartRate"])
        assertEquals(GarminProtocol.Value(14.5, time), sample.additional["respirationRate"])
        assertEquals(GarminProtocol.Value(12345, time), sample.additional["stepsToday"])
        assertEquals(GarminProtocol.Value(89.125, time), sample.additional["watchBattery"])
        assertEquals(GarminProtocol.Value(98.5, time - 300), sample.additional["oxygenSaturation"])
        assertFalse(sample.additional.containsKey("solarIntensity"))
        assertTrue(GarminProtocol.decode(packet(row())).single().additional.isEmpty())
    }

    @Test fun addedMetricsRejectInvalidAndUnpairedValues() {
        for ((key, value) in listOf(
            "heartRate" to 255, "heartRate" to 0,
            "respirationRate" to Double.NaN,
            "watchBattery" to Double.POSITIVE_INFINITY,
            "watchBattery" to 100.1,
            "oxygenSaturation" to 0, "oxygenSaturation" to 101,
            "stepsToday" to -1, "stepsToday" to 1.5,
            "solarIntensity" to -1,
        )) {
            assertThrows(key, RuntimeException::class.java) {
                GarminProtocol.decode(packet(row() + mapOf(key to value, "${key}At" to time)))
            }
        }
        assertThrows(RuntimeException::class.java) {
            GarminProtocol.decode(packet(row() + mapOf("watchBattery" to 40.5)))
        }
    }

    @Test fun badInputIsNotEligibleForAcknowledgement() {
        val bad = listOf(
            packet(row() + ("stress" to -1)),
            packet(row() + ("bodyBattery" to 101)),
            packet(row() + ("stress" to 2.5)),
            packet(row() + ("stressAt" to null)),
            packet(row() + ("capturedAt" to 0)),
            packet(row() + ("bodyBatteryAt" to time + 61)),
            packet(row()) + ("schema" to 2),
            packet(row()) + ("type" to "other"),
            packet(),
            packet(*Array(13) { row(time + it * 300) }),
            packet(row(), row()),
            packet(row(time + 300), row()),
        )
        for (message in bad) {
            assertThrows("must reject $message", RuntimeException::class.java) { GarminProtocol.decode(message) }
        }
    }

    @Test fun retriesAndOutOfOrderDeliveriesPreserveTheFirstReceipt() {
        val sample = GarminProtocol.decode(packet(row())).single()
        val original = GarminProtocol.Receipt(1, "Enduro 3", time + 1, sample)
        val retry = original.copy(receivedAt = time + 300)
        val newer = original.copy(sample = sample.copy(capturedAt = time + 300))
        assertEquals(listOf(original, newer), GarminProtocol.merge(listOf(newer, original), listOf(retry)))
        assertEquals(2, GarminProtocol.merge(listOf(original), listOf(original.copy(deviceId = 2))).size)
    }

    @Test fun inboxRetainsABoundedRollingHistory() {
        val sample = GarminProtocol.decode(packet(row())).single()
        val receipts = (0..300).map {
            GarminProtocol.Receipt(1, "Enduro 3", time, sample.copy(capturedAt = time + it * 300))
        }
        val result = GarminProtocol.merge(emptyList(), receipts)
        assertEquals(288, result.size)
        assertEquals(receipts.last(), result.last())
    }
}
