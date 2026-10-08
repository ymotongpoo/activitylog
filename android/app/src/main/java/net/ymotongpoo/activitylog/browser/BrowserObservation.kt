package net.ymotongpoo.activitylog.browser

import org.json.JSONException
import org.json.JSONObject

/** Address bar text captured by the accessibility service at a point in time. */
data class BrowserObservation(
    val timeMs: Long,
    val packageName: String,
    val text: String,
) {
    fun toJsonLine(): String =
        JSONObject().put("t", timeMs).put("p", packageName).put("u", text).toString()

    companion object {
        fun fromJsonLine(line: String): BrowserObservation? {
            if (line.isBlank()) return null
            return try {
                val o = JSONObject(line)
                BrowserObservation(o.getLong("t"), o.getString("p"), o.getString("u"))
            } catch (_: JSONException) {
                null
            }
        }
    }
}
