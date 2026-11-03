package net.ymotongpoo.activitylog.browser

import net.ymotongpoo.activitylog.storage.DurableFiles
import java.io.File
import java.io.FileOutputStream

/**
 * App-private JSONL queue written by the accessibility service and drained by the
 * collection job. Both run in the same process, so a process-wide lock is enough.
 *
 * Draining is two-phase: [beginDrain] moves the queue into a processing file and
 * [commitDrain] deletes it once the collection state has been saved. If the job dies
 * in between, the processing file is read again together with new observations.
 */
class ObservationQueue(dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val queueFile = File(dir, "browser_observations.jsonl")
    private val processingFile = File(dir, "browser_observations.processing.jsonl")

    fun append(observation: BrowserObservation): Boolean = synchronized(LOCK) {
        if (queueFile.length() + processingFile.length() > maxBytes) return false
        queueFile.parentFile?.mkdirs()
        FileOutputStream(queueFile, true).use {
            it.write(("\n" + observation.toJsonLine() + "\n").toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        DurableFiles.syncDirectory(queueFile.parentFile!!)
        true
    }

    fun beginDrain(): List<BrowserObservation> = synchronized(LOCK) {
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
        processingFile.readLines(Charsets.UTF_8).mapNotNull(BrowserObservation::fromJsonLine)
    }

    fun commitDrain() {
        synchronized(LOCK) {
            if (processingFile.exists()) {
                if (!processingFile.delete()) throw java.io.IOException("cannot delete $processingFile")
                DurableFiles.syncDirectory(processingFile.parentFile!!)
            }
        }
    }

    fun pendingBytes(): Long = synchronized(LOCK) { queueFile.length() + processingFile.length() }

    companion object {
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024
        private val LOCK = Any()
    }
}
