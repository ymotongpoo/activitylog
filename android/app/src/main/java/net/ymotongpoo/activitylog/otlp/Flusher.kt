package net.ymotongpoo.activitylog.otlp

data class FlushResult(
    val sent: Int,
    val dropped: Int,
    val remaining: Int,
    /** True when sending stopped because of a retryable failure. */
    val retryable: Boolean,
    val lastError: String?,
)

/** Sends spooled payloads oldest-first, stopping at the first retryable failure. */
class Flusher(
    private val spool: Spool,
    private val send: (Signal, ByteArray) -> SendResult,
    private val onRejected: (Signal, SendResult.Rejected) -> Unit,
) {
    fun flush(): FlushResult {
        var sent = 0
        var dropped = 0
        var lastError: String? = null
        for (entry in spool.entries()) {
            val body = try {
                spool.read(entry)
            } catch (e: java.io.IOException) {
                spool.delete(entry)
                dropped++
                lastError = "unreadable spool file: ${e.message}"
                continue
            }
            when (val r = send(entry.signal, body)) {
                SendResult.Success -> {
                    spool.delete(entry)
                    sent++
                }
                is SendResult.Retryable -> {
                    return FlushResult(sent, dropped, spool.count(), true, r.reason)
                }
                is SendResult.Rejected -> {
                    spool.delete(entry)
                    dropped++
                    lastError = "HTTP ${r.status} for ${entry.signal.id}: ${r.body.take(300)}"
                    onRejected(entry.signal, r)
                }
            }
        }
        return FlushResult(sent, dropped, spool.count(), false, lastError)
    }
}
