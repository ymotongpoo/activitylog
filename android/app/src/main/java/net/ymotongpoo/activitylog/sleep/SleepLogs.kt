package net.ymotongpoo.activitylog.sleep

import net.ymotongpoo.activitylog.otlp.LogData
import net.ymotongpoo.activitylog.otlp.Severity
import net.ymotongpoo.activitylog.otlp.attr
import net.ymotongpoo.activitylog.otlp.millisToNanos
import org.json.JSONArray

object SleepLogs {
    fun build(changes: List<SleepReconciler.Change>, observedMs: Long): List<LogData> = changes.map { change ->
        val session = change.session
        val operation = if (change.deleted) "delete" else "upsert"
        val revision = SleepSession.sha256("$operation:${session.fingerprint()}")
        val body = SleepCodec.session(session).apply {
            put("schema", 1)
            put("operation", operation)
            put("revision", revision)
            put("observed_unix_ms", observedMs)
            put("session_count", if (change.deleted) 0 else 1)
            put("duration_seconds", if (change.deleted) 0.0 else (session.endMs - session.startMs) / 1000.0)
            put("stage_count", if (change.deleted) 0 else session.stages.size)
            for (stage in SleepStage.entries) put("${stage.key}_seconds", if (change.deleted) 0.0 else
                session.stages.filter { it.stage == stage }.sumOf { it.endMs - it.startMs } / 1000.0)
            if (change.deleted) put("stages", JSONArray())
        }.toString()
        require(body.toByteArray(Charsets.UTF_8).size <= 240 * 1024) { "Sleep snapshot exceeds log size limit" }
        LogData(
            // Import time avoids rejecting overnight/backfilled/corrected records as old samples.
            // The complete original interval is preserved in the body, never resampled to five minutes.
            timeUnixNano = observedMs.millisToNanos(), observedTimeUnixNano = observedMs.millisToNanos(),
            severity = Severity.INFO, body = body,
            attributes = listOf(
                attr("event.name", "sleep.session"), attr("event.id", "${session.id}:$revision"),
                attr("sleep.session.id", session.id), attr("sleep.source.package", session.source),
                attr("sleep.operation", operation),
            ),
        )
    }
}
