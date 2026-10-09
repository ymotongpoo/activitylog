package net.ymotongpoo.activitylog.location

import org.json.JSONException
import org.json.JSONObject

/** A location reported by the platform. Optional values are null when unknown. */
data class LocationFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double? = null,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val provider: String = "",
    /** "periodic" for the collection job's own request, "passive" for fixes requested by other apps. */
    val source: String = "",
    val mock: Boolean = false,
) {
    fun toJsonLine(): String = JSONObject().apply {
        put("t", timeMs)
        put("lat", lat)
        put("lon", lon)
        accuracyM?.let { put("acc", it) }
        altitudeM?.let { put("alt", it) }
        speedMps?.let { put("spd", it) }
        bearingDeg?.let { put("brg", it) }
        put("p", provider)
        put("s", source)
        if (mock) put("mock", true)
    }.toString()

    companion object {
        fun fromJsonLine(line: String): LocationFix? {
            if (line.isBlank()) return null
            return try {
                val o = JSONObject(line)
                fun opt(key: String): Double? = if (o.has(key)) o.getDouble(key) else null
                LocationFix(
                    timeMs = o.getLong("t"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    accuracyM = opt("acc"),
                    altitudeM = opt("alt"),
                    speedMps = opt("spd"),
                    bearingDeg = opt("brg"),
                    provider = o.optString("p"),
                    source = o.optString("s"),
                    mock = o.optBoolean("mock"),
                )
            } catch (_: JSONException) {
                null
            }
        }
    }
}

/** How much the coordinates are rounded before they are sent. */
enum class LocationPrecision(val id: String, val decimals: Int?) {
    FULL("full", null),
    M100("100m", 3),
    KM1("1km", 2),
    ;

    companion object {
        fun fromId(id: String?): LocationPrecision = entries.firstOrNull { it.id == id } ?: FULL
    }
}
