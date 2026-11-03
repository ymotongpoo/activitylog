package net.ymotongpoo.activitylog.otlp

import net.ymotongpoo.activitylog.storage.DurableFiles
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Unchanged OTLP bodies, with retry state persisted atomically in their filenames. */
class RejectedQueue(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryIntervalMs: Long = TimeUnit.HOURS.toMillis(6),
    private val maxAgeMs: Long = TimeUnit.DAYS.toMillis(7),
    private val maxAttempts: Int = 20,
    private val maxBytes: Long = Spool.DEFAULT_MAX_BYTES,
) {
    data class Entry(val file: File, val signal: Signal, val since: Long, val attempts: Int, val next: Long, val id: String)
    data class ReplayResult(val sent: Int, val attempted: Int, val deleted: Int, val remaining: Int)

    /** Move, never delete then copy. Both queues must be on the same filesystem. */
    fun retain(entry: Spool.Entry): Int {
        if (dir.mkdirs()) DurableFiles.syncDirectory(dir.parentFile!!)
        val now = clock()
        val target = file(now, 0, now + retryIntervalMs, UUID.randomUUID().toString(), entry.signal)
        move(entry.file, target)
        return prune()
    }

    fun entries(): List<Entry> = dir.listFiles().orEmpty().mapNotNull { f ->
        if (!f.isFile || !f.name.endsWith(".json.gz")) return@mapNotNull null
        val parts = f.name.removeSuffix(".json.gz").split('.', limit = 5)
        if (parts.size != 5) return@mapNotNull null
        val since = parts[0].toLongOrNull() ?: return@mapNotNull null
        val attempts = parts[1].toIntOrNull() ?: return@mapNotNull null
        val next = parts[2].toLongOrNull() ?: return@mapNotNull null
        val signal = Signal.fromId(parts[4]) ?: return@mapNotNull null
        Entry(f, signal, since, attempts, next, parts[3])
    }.sortedWith(compareBy<Entry> { it.since }.thenBy { it.id })

    fun count(): Int = entries().size

    /** Also called by offline collection, so expiry does not require connectivity. */
    fun prune(): Int {
        val now = clock()
        var deleted = 0
        val entries = entries()
        var bytes = entries.sumOf { it.file.length() }
        for (entry in entries) {
            if (now - entry.since >= maxAgeMs || entry.attempts >= maxAttempts) {
                bytes -= entry.file.length()
                remove(entry.file)
                deleted++
            }
        }
        for (entry in entries) {
            if (bytes <= maxBytes) break
            if (entry.file.exists()) {
                bytes -= entry.file.length()
                remove(entry.file)
                deleted++
            }
        }
        return deleted
    }

    /** Bound each worker run; retries never occupy the normal spool's head. */
    fun replay(limit: Int = 5, send: (Signal, ByteArray) -> SendResult): ReplayResult {
        var deleted = prune()
        var sent = 0
        var attempted = 0
        for (entry in entries().filter { it.next <= clock() }.take(limit)) {
            val body = entry.file.readBytes()
            // Reserve before sending: a process death must not reset the retry budget.
            val reserved = file(entry.since, entry.attempts + 1, clock() + retryIntervalMs, entry.id, entry.signal)
            move(entry.file, reserved)
            attempted++
            when (send(entry.signal, body)) {
                SendResult.Success -> { remove(reserved); sent++ }
                else -> if (entry.attempts + 1 >= maxAttempts) { remove(reserved); deleted++ }
            }
        }
        return ReplayResult(sent, attempted, deleted, count())
    }

    private fun file(since: Long, attempts: Int, next: Long, id: String, signal: Signal) =
        File(dir, "$since.$attempts.$next.$id.${signal.id}.json.gz")

    private fun move(source: File, target: File) {
        if (!source.renameTo(target)) throw IOException("cannot retain/re-schedule ${source.name}")
        DurableFiles.syncDirectory(target.parentFile!!)
        if (source.parentFile != target.parentFile) DurableFiles.syncDirectory(source.parentFile!!)
    }

    private fun remove(file: File) {
        if (!file.delete()) throw IOException("cannot remove ${file.name}")
        DurableFiles.syncDirectory(dir)
    }
}
