package net.ymotongpoo.activitylog.otlp

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RejectedQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1000L
    private fun queue(maxAttempts: Int = 20, maxBytes: Long = 10000) = RejectedQueue(
        File(tmp.root, "rejected"), clock = { now }, retryIntervalMs = 100,
        maxAgeMs = 1000, maxAttempts = maxAttempts, maxBytes = maxBytes,
    )
    private fun retain(body: ByteArray = byteArrayOf(1), q: RejectedQueue = queue()) {
        val spool = Spool(File(tmp.root, "spool"), clock = { now })
        spool.put(Signal.METRICS, body)
        q.retain(spool.entries().single())
        assertEquals(0, spool.count())
    }

    @Test fun rejectionDoesNotBlockNewPayloadAndCanLaterRecover() {
        val spool = Spool(File(tmp.root, "spool"), clock = { now })
        spool.put(Signal.METRICS, byteArrayOf(1))
        spool.put(Signal.LOGS, byteArrayOf(2))
        val result = Flusher(spool, { _, body ->
            if (body[0].toInt() == 1) SendResult.Rejected(400, "too old") else SendResult.Success
        }, queue(), { _, _ -> }).flush()
        assertEquals(1, result.sent)
        assertEquals(1, result.retained)
        assertEquals(0, result.dropped)
        assertEquals(0, spool.count())
        now += 100
        assertEquals(1, queue().replay { _, body ->
            assertArrayEquals(byteArrayOf(1), body)
            SendResult.Success
        }.sent)
    }

    @Test fun persistsOriginalBodyAndScheduleAcrossRestart() {
        val body = Gzip.compress("{\"timeUnixNano\":\"12345\"}")
        retain(body)
        assertEquals(0, queue().replay { _, _ -> error("not due") }.attempted)
        now += 100
        val failed = queue().replay { _, actual ->
            assertArrayEquals(body, actual)
            SendResult.Rejected(400, "too old")
        }
        assertEquals(1, failed.attempted)
        assertEquals(1, queue().entries().single().attempts)
        assertEquals(0, queue().replay { _, _ -> error("must wait") }.attempted)
        now += 100
        val recovered = queue().replay { signal, actual ->
            assertEquals(Signal.METRICS, signal)
            assertArrayEquals(body, actual)
            SendResult.Success
        }
        assertEquals(1, recovered.sent)
        assertEquals(0, queue().count())
    }

    @Test fun deletesAtAttemptLimitIncludingTransientFailures() {
        retain(q = queue(maxAttempts = 2))
        repeat(2) {
            now += 100
            queue(maxAttempts = 2).replay { _, _ -> SendResult.Retryable("offline") }
        }
        assertEquals(0, queue().count())
    }

    @Test fun expiresWithoutNetworkEvenAfterRestart() {
        retain()
        now += 999
        assertEquals(0, queue().prune())
        now++
        assertEquals(1, queue().prune())
        assertEquals(0, queue().count())
    }

    @Test fun reservesAttemptBeforeSendingEvenIfProcessStops() {
        retain()
        now += 100
        try {
            queue().replay { _, _ -> throw IllegalStateException("simulated crash") }
            fail("expected crash")
        } catch (_: IllegalStateException) { }
        assertEquals(1, queue().entries().single().attempts)
        assertEquals(0, queue().replay { _, _ -> error("must wait after crash") }.attempted)
    }

    @Test fun replayIsBoundedAndDoesNotStarveOtherEntries() {
        repeat(7) { retain(byteArrayOf(it.toByte())) }
        now += 100
        assertEquals(5, queue().replay { _, _ -> SendResult.Rejected(400, "still old") }.attempted)
        assertEquals(2, queue().replay { _, _ -> SendResult.Success }.sent)
        assertEquals(5, queue().count())
    }

    @Test fun failedMovePreservesNormalSpool() {
        val dir = File(tmp.root, "not-a-directory").apply { writeText("x") }
        val spool = Spool(File(tmp.root, "spool"))
        spool.put(Signal.LOGS, byteArrayOf(7))
        try {
            RejectedQueue(dir).retain(spool.entries().single())
            fail("expected IOException")
        } catch (_: java.io.IOException) { }
        assertEquals(1, spool.count())
    }

    @Test fun boundsDiskUsageByDroppingOldest() {
        retain(ByteArray(6) { 1 }, queue(maxBytes = 10))
        now++
        retain(ByteArray(6) { 2 }, queue(maxBytes = 10))
        assertEquals(1, queue().count())
        assertEquals(2, queue().entries().single().file.readBytes()[0].toInt())
    }

    @Test fun defaultPolicyExpiresAfterSevenDays() {
        val dir = File(tmp.root, "rejected")
        val q = RejectedQueue(dir, clock = { now })
        retain(q = q)
        now += java.util.concurrent.TimeUnit.HOURS.toMillis(6) - 1
        assertEquals(0, q.replay { _, _ -> error("too early") }.attempted)
        now = 1000L + java.util.concurrent.TimeUnit.DAYS.toMillis(7)
        assertEquals(1, q.prune())
    }
}
