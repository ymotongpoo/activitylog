package net.ymotongpoo.activitylog.otlp

import java.io.File
import java.util.Locale

/**
 * Durable outbox of gzip-compressed OTLP request bodies. Every payload goes through the
 * spool first and is sent oldest-first, so a payload that failed with a retryable error
 * stays at the head of the queue until the next attempt.
 */
class Spool(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val file: File, val signal: Signal)

    private var seq = 0

    /** Stores a payload. Returns the files dropped to stay under the size cap. */
    @Synchronized
    fun put(signal: Signal, gzBody: ByteArray): List<File> {
        dir.mkdirs()
        var target: File
        do {
            seq = (seq + 1) % 1_000_000
            val name = String.format(Locale.ROOT, "%013d-%06d.%s.json.gz", clock(), seq, signal.id)
            target = File(dir, name)
        } while (target.exists())
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(gzBody)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("cannot move spool file into place: $target")
        }
        return trim()
    }

    /** Pending entries, oldest first. */
    @Synchronized
    fun entries(): List<Entry> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name.endsWith(SUFFIX) }
            .sortedBy { it.name }
            .mapNotNull { f ->
                val id = f.name.removeSuffix(SUFFIX).substringAfterLast('.', "")
                Signal.fromId(id)?.let { Entry(f, it) }
            }
    }

    fun read(entry: Entry): ByteArray = entry.file.readBytes()

    @Synchronized
    fun delete(entry: Entry) {
        entry.file.delete()
    }

    @Synchronized
    fun totalBytes(): Long = entries().sumOf { it.file.length() }

    @Synchronized
    fun count(): Int = entries().size

    private fun trim(): List<File> {
        val all = entries()
        var total = all.sumOf { it.file.length() }
        val dropped = mutableListOf<File>()
        for (e in all) {
            if (total <= maxBytes) break
            total -= e.file.length()
            e.file.delete()
            dropped += e.file
        }
        return dropped
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
        private const val SUFFIX = ".json.gz"
    }
}
