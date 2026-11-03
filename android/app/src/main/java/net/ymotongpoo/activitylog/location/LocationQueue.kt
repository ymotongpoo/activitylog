package net.ymotongpoo.activitylog.location

import net.ymotongpoo.activitylog.storage.DurableFiles
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
        if (queueFile.length() + processingFile.length() > maxBytes) return false
        queueFile.parentFile?.mkdirs()
        FileOutputStream(queueFile, true).use {
            it.write(("\n" + fix.toJsonLine() + "\n").toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        DurableFiles.syncDirectory(queueFile.parentFile!!)
        true
    }

    fun beginDrain(): List<LocationFix> = synchronized(LOCK) {
        if (queueFile.exists()) {
            if (processingFile.exists()) {
                // Persist the merged batch before removing its source. A crash between
                // these steps can replay data, but cannot discard uncommitted records.
                val merged = File(processingFile.path + ".tmp")
                FileOutputStream(merged).use {
                    it.write(processingFile.readBytes())
                    it.write('\n'.code)
                    it.write(queueFile.readBytes())
                    it.fd.sync()
                }
                if (!merged.renameTo(processingFile)) {
                    throw java.io.IOException("cannot replace $processingFile")
                }
                DurableFiles.syncDirectory(queueFile.parentFile!!)
                if (!queueFile.delete()) throw java.io.IOException("cannot delete $queueFile")
            } else if (!queueFile.renameTo(processingFile)) {
                throw java.io.IOException("cannot move queue into processing: $queueFile")
            }
            DurableFiles.syncDirectory(queueFile.parentFile!!)
        }
        if (!processingFile.exists()) return emptyList()
        processingFile.readLines(Charsets.UTF_8).mapNotNull(LocationFix::fromJsonLine)
    }

    fun commitDrain() {
        synchronized(LOCK) {
            if (processingFile.exists()) {
                if (!processingFile.delete()) throw java.io.IOException("cannot delete $processingFile")
                DurableFiles.syncDirectory(processingFile.parentFile!!)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024
        private val LOCK = Any()
    }
}
