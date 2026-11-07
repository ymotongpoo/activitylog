package net.ymotongpoo.activitylog.sleep

import java.security.MessageDigest

/** Health Connect stage codes. Unknown intervals are never inferred to be awake or asleep. */
enum class SleepStage(val code: Int, val label: String) {
    UNKNOWN(0, "Unknown"), AWAKE(1, "Awake"), SLEEPING(2, "Sleeping (unclassified)"),
    OUT_OF_BED(3, "Out of bed"), LIGHT(4, "Light"), DEEP(5, "Deep"), REM(6, "REM"),
    AWAKE_IN_BED(7, "Awake in bed");

    val key: String get() = name.lowercase(java.util.Locale.ROOT)
    companion object { fun fromCode(code: Int): SleepStage = entries.find { it.code == code } ?: UNKNOWN }
}

data class SleepInterval(val startMs: Long, val endMs: Long, val stage: SleepStage)

data class SleepSession(
    /** Opaque, deterministic ID derived from the data origin and Health Connect record ID. */
    val id: String,
    val source: String,
    val startMs: Long,
    val endMs: Long,
    val modifiedMs: Long,
    val startOffsetSeconds: Int?,
    val endOffsetSeconds: Int?,
    val stages: List<SleepInterval>,
) {
    fun normalized(): SleepSession {
        require(id.isNotBlank() && source.isNotBlank() && startMs > 0 && endMs > startMs)
        require(endMs - startMs <= 7L * 24 * 60 * 60 * 1000) { "Sleep session exceeds seven days" }
        require(stages.size <= 4096) { "Too many sleep intervals" }
        val result = mutableListOf<SleepInterval>()
        fun append(interval: SleepInterval) {
            val previous = result.lastOrNull()
            if (previous?.endMs == interval.startMs && previous.stage == interval.stage) {
                result[result.lastIndex] = previous.copy(endMs = interval.endMs)
            } else result += interval
        }
        var cursor = startMs
        for (interval in stages.sortedBy { it.startMs }) {
            require(interval.startMs >= cursor && interval.endMs > interval.startMs && interval.endMs <= endMs) {
                "Sleep intervals overlap or exceed their session"
            }
            if (interval.startMs > cursor) append(SleepInterval(cursor, interval.startMs, SleepStage.UNKNOWN))
            append(interval)
            cursor = interval.endMs
        }
        if (cursor < endMs) append(SleepInterval(cursor, endMs, SleepStage.UNKNOWN))
        return copy(stages = result)
    }

    fun fingerprint(): String = sha256(buildString {
        append(source).append('|').append(startMs).append('|').append(endMs)
        append('|').append(startOffsetSeconds).append('|').append(endOffsetSeconds)
        stages.forEach { append('|').append(it.startMs).append(':').append(it.endMs).append(':').append(it.stage.code) }
    })

    companion object {
        const val GARMIN_PACKAGE = "com.garmin.android.apps.connectmobile"
        fun opaqueId(source: String, recordId: String): String {
            require(recordId.isNotBlank()) { "Missing Health Connect record ID" }
            return sha256("$source\u0000$recordId").take(32)
        }
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** A complete, paginated read is reconciled at once. No checkpoint is made after a partial read. */
object SleepReconciler {
    data class Change(val session: SleepSession, val deleted: Boolean)
    data class Update(val sessions: Map<String, SleepSession>, val changes: List<Change>)

    fun reconcile(previous: Map<String, SleepSession>, incoming: List<SleepSession>, fromMs: Long, toMs: Long): Update {
        require(fromMs < toMs)
        val current = incoming.map { it.normalized() }
        require(current.all { it.source == SleepSession.GARMIN_PACKAGE && it.endMs <= toMs })
        require(current.map { it.id }.distinct().size == current.size) { "Duplicate sleep record IDs" }
        val next = previous.filterValues { it.endMs >= fromMs }.toMutableMap()
        val changes = mutableListOf<Change>()
        val ids = current.map { it.id }.toSet()
        for (session in current.sortedWith(compareBy({ it.startMs }, { it.id }))) {
            if (previous[session.id]?.fingerprint() != session.fingerprint()) changes += Change(session, false)
            next[session.id] = session
        }
        for (session in previous.values.sortedBy { it.id }) {
            // Only infer deletions for records wholly inside the completed read window.
            // Aging out of the window is not a deletion of historical sleep.
            if (session.startMs >= fromMs && session.endMs <= toMs && session.id !in ids) {
                changes += Change(session, true)
                next.remove(session.id)
            }
        }
        return Update(next, changes)
    }
}
