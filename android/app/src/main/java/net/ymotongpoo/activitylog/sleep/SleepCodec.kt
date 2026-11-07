package net.ymotongpoo.activitylog.sleep

import org.json.JSONArray
import org.json.JSONObject

/** The same complete session snapshot is used locally and in the OTLP log body. */
object SleepCodec {
    fun session(session: SleepSession): JSONObject = JSONObject().apply {
        put("session_id", session.id)
        put("source_package", session.source)
        put("start_unix_ms", session.startMs)
        put("end_unix_ms", session.endMs)
        put("modified_unix_ms", session.modifiedMs)
        put("start_offset_seconds", session.startOffsetSeconds ?: JSONObject.NULL)
        put("end_offset_seconds", session.endOffsetSeconds ?: JSONObject.NULL)
        put("stages", JSONArray().apply {
            for (interval in session.stages) put(JSONObject().apply {
                put("start_unix_ms", interval.startMs)
                put("end_unix_ms", interval.endMs)
                put("stage", interval.stage.key)
                put("stage_code", interval.stage.code)
            })
        })
    }

    fun decode(row: JSONObject): SleepSession {
        val intervals = row.getJSONArray("stages")
        return SleepSession(
            row.getString("session_id"), row.getString("source_package"),
            row.getLong("start_unix_ms"), row.getLong("end_unix_ms"), row.getLong("modified_unix_ms"),
            if (row.isNull("start_offset_seconds")) null else row.getInt("start_offset_seconds"),
            if (row.isNull("end_offset_seconds")) null else row.getInt("end_offset_seconds"),
            (0 until intervals.length()).map { index -> intervals.getJSONObject(index).let {
                SleepInterval(it.getLong("start_unix_ms"), it.getLong("end_unix_ms"), SleepStage.fromCode(it.getInt("stage_code")))
            } },
        ).normalized()
    }

    fun encodeState(sessions: Map<String, SleepSession>): String = JSONArray().apply {
        sessions.values.sortedBy { it.id }.forEach { put(session(it)) }
    }.toString()

    fun decodeState(json: String): Map<String, SleepSession> {
        val rows = JSONArray(json)
        return (0 until rows.length()).map { decode(rows.getJSONObject(it)) }.associateBy { it.id }
    }
}
