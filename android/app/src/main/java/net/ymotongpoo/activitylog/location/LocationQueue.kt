package net.ymotongpoo.activitylog.location

import java.io.File
import java.io.FileOutputStream

/**
 * App-private JSONL queue of location fixes, appended by [LocationReceiver] and the
 * collection job and drained by the collection job. Draining is two-phase like the
 * browser observation queue: [beginDrain] then [commitDrain] after spooling.
 */
class LocationQueue(dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val queueFile = File(dir, "locations.jsonl")
    private val processingFile = File(dir, "locations.processing.jsonl")

    fun append(fix: LocationFix): Boolean = synchronized(LOCK) {
        if (queueFile.length() > maxBytes) return false
        queueFile.parentFile?.mkdirs()
        FileOutputStream(queueFile, true).use {
            it.write((fix.toJsonLine() + "\n").toByteArray(Charsets.UTF_8))
        }
        true
    }

    fun beginDrain(): List<LocationFix> = synchronized(LOCK) {
        if (processingFile.length() > maxBytes) processingFile.delete()
        if (queueFile.exists()) {
            if (processingFile.exists()) {
                processingFile.appendBytes(queueFile.readBytes())
                queueFile.delete()
            } else if (!queueFile.renameTo(processingFile)) {
                processingFile.writeBytes(queueFile.readBytes())
                queueFile.delete()
            }
        }
        if (!processingFile.exists()) return emptyList()
        processingFile.readLines(Charsets.UTF_8).mapNotNull(LocationFix::fromJsonLine)
    }

    fun commitDrain() {
        synchronized(LOCK) { processingFile.delete() }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024
        private val LOCK = Any()
    }
}
