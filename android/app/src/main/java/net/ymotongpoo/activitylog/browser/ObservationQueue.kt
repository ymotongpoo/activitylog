package net.ymotongpoo.activitylog.browser

import java.io.File
import java.io.FileOutputStream

/**
 * App-private JSONL queue written by the accessibility service and drained by the
 * collection job. Both run in the same process, so a process-wide lock is enough.
 *
 * Draining is two-phase: [beginDrain] moves the queue into a processing file and
 * [commitDrain] deletes it once the collection state has been saved. If the job dies
 * in between, the processing file is read again together with the events that were
 * not checkpointed either.
 */
class ObservationQueue(dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val queueFile = File(dir, "browser_observations.jsonl")
    private val processingFile = File(dir, "browser_observations.processing.jsonl")

    fun append(observation: BrowserObservation): Boolean = synchronized(LOCK) {
        if (queueFile.length() > maxBytes) return false
        queueFile.parentFile?.mkdirs()
        FileOutputStream(queueFile, true).use {
            it.write((observation.toJsonLine() + "\n").toByteArray(Charsets.UTF_8))
        }
        true
    }

    fun beginDrain(): List<BrowserObservation> = synchronized(LOCK) {
        // Never let an uncommitted backlog grow without bound.
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
        processingFile.readLines(Charsets.UTF_8).mapNotNull(BrowserObservation::fromJsonLine)
    }

    fun commitDrain() {
        synchronized(LOCK) { processingFile.delete() }
    }

    fun pendingBytes(): Long = synchronized(LOCK) { queueFile.length() + processingFile.length() }

    companion object {
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024
        private val LOCK = Any()
    }
}
