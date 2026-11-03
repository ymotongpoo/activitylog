package net.ymotongpoo.activitylog.otlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpoolTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val rejectedQueue get() = RejectedQueue(java.io.File(tmp.root, "rejected"))

    private var clock = 1_000L

    @Test
    fun retriesPersistedPayloadsAfterRestartWithoutChangingTheirBytes() {
        val original = Spool(tmp.root, clock = { clock++ })
        val bodies = Signal.entries.map { signal ->
            Gzip.compress("{\"signal\":\"${signal.id}\",\"timestamp\":1000}")
        }
        Signal.entries.zip(bodies).forEach { (signal, body) -> original.put(signal, body) }
        val offline = Flusher(original, { _, _ -> SendResult.Retryable("offline") }, rejectedQueue, { _, _ -> }).flush()
        assertTrue(offline.retryable)
        assertEquals(3, offline.remaining)
        val restarted = Spool(tmp.root)
        val received = mutableListOf<Pair<Signal, List<Byte>>>()
        val online = Flusher(restarted, { signal, body ->
            received += signal to body.toList()
            SendResult.Success
        }, rejectedQueue, { _, _ -> }).flush()
        assertEquals(Signal.entries.zip(bodies.map { it.toList() }), received)
        assertEquals(3, online.sent)
        assertEquals(0, Spool(tmp.root).count())
    }

    @Test
    fun entriesAreOldestFirst() {
        val spool = Spool(tmp.root, clock = { clock })
        spool.put(Signal.TRACES, byteArrayOf(1))
        spool.put(Signal.LOGS, byteArrayOf(2))
        clock = 2_000L
        spool.put(Signal.METRICS, byteArrayOf(3))
        assertEquals(listOf(Signal.TRACES, Signal.LOGS, Signal.METRICS), spool.entries().map { it.signal })
        assertEquals(listOf(1, 2, 3), spool.entries().map { spool.read(it)[0].toInt() })
    }

    @Test
    fun dropsOldestOverCap() {
        val spool = Spool(tmp.root, maxBytes = 25, clock = { clock++ })
        spool.put(Signal.TRACES, ByteArray(10) { 1 })
        spool.put(Signal.TRACES, ByteArray(10) { 2 })
        val dropped = spool.put(Signal.TRACES, ByteArray(10) { 3 })
        assertEquals(1, dropped.size)
        assertEquals(listOf(2, 3), spool.entries().map { spool.read(it)[0].toInt() })
        assertEquals(20L, spool.totalBytes())
    }

    @Test
    fun flusherStopsAtRetryableAndRetainsRejected() {
        val spool = Spool(tmp.root, clock = { clock++ })
        spool.put(Signal.TRACES, byteArrayOf(1))
        spool.put(Signal.LOGS, byteArrayOf(2))
        spool.put(Signal.METRICS, byteArrayOf(3))
        spool.put(Signal.TRACES, byteArrayOf(4))
        val rejected = mutableListOf<Int>()
        val responses = mapOf(
            1 to SendResult.Success,
            2 to SendResult.Rejected(400, "bad"),
            3 to SendResult.Retryable("HTTP 503"),
        )
        val result = Flusher(
            spool,
            rejectedQueue = rejectedQueue,
            send = { _, body -> responses.getValue(body[0].toInt()) },
            onRejected = { _, r -> rejected += r.status },
        ).flush()
        assertEquals(1, result.sent)
        assertEquals(0, result.dropped)
        assertEquals(1, result.retained)
        assertEquals(1, rejectedQueue.count())
        assertTrue(result.retryable)
        assertEquals(2, result.remaining)
        assertEquals(listOf(400), rejected)
        assertEquals(listOf(3, 4), spool.entries().map { spool.read(it)[0].toInt() })
    }

    @Test
    fun classifiesResponses() {
        assertEquals(SendResult.Success, OtlpSender.classify(200, ""))
        assertEquals(SendResult.Success, OtlpSender.classify(204, ""))
        assertTrue(OtlpSender.classify(429, "") is SendResult.Retryable)
        assertTrue(OtlpSender.classify(503, "") is SendResult.Retryable)
        assertTrue(OtlpSender.classify(408, "") is SendResult.Retryable)
        assertEquals(SendResult.Rejected(401, "invalid token"), OtlpSender.classify(401, "invalid token"))
        assertEquals(SendResult.Rejected(400, ""), OtlpSender.classify(400, ""))
    }

    @Test
    fun basicAuthHeader() {
        assertEquals("Basic MTIzNDU2OmdsY190b2tlbg==", OtlpSender.basicAuth("123456", "glc_token"))
    }
}
