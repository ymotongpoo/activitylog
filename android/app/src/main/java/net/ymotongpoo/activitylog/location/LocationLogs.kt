package net.ymotongpoo.activitylog.location

import net.ymotongpoo.activitylog.otlp.Attribute
import net.ymotongpoo.activitylog.otlp.LogData
import net.ymotongpoo.activitylog.otlp.Severity
import net.ymotongpoo.activitylog.otlp.attr
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Turns location fixes into `device.location` log records. */
object LocationLogs {
    const val EVENT = "device.location"

    /** Fixes closer in time than this with the same coordinates are duplicates. */
    private const val DUPLICATE_WINDOW_MS = 1_000L

    fun build(fixes: List<LocationFix>, precision: LocationPrecision, nowMs: Long): List<LogData> {
        val out = mutableListOf<LogData>()
        var previous: LocationFix? = null
        for (fix in fixes.sortedBy { it.timeMs }) {
            val lat = round(fix.lat, precision)
            val lon = round(fix.lon, precision)
            val p = previous
            if (p != null && fix.timeMs - p.timeMs < DUPLICATE_WINDOW_MS &&
                round(p.lat, precision) == lat && round(p.lon, precision) == lon
            ) {
                continue
            }
            previous = fix
            out += record(fix, lat, lon, precision, nowMs)
        }
        return out
    }

    private fun record(fix: LocationFix, lat: Double, lon: Double, precision: LocationPrecision, nowMs: Long): LogData {
        val attrs = mutableListOf<Attribute>(
            attr("event.name", EVENT),
            attr("geo.location.lat", lat),
            attr("geo.location.lon", lon),
            attr("activity.location.precision", precision.id),
        )
        fix.accuracyM?.let { attrs += attr("activity.location.accuracy", it) }
        fix.altitudeM?.let { attrs += attr("activity.location.altitude", it) }
        fix.speedMps?.let { attrs += attr("activity.location.speed", it) }
        fix.bearingDeg?.let { attrs += attr("activity.location.bearing", it) }
        if (fix.provider.isNotEmpty()) attrs += attr("activity.location.provider", fix.provider)
        if (fix.source.isNotEmpty()) attrs += attr("activity.location.source", fix.source)
        if (fix.mock) attrs += attr("activity.location.mock", true)
        val accuracy = fix.accuracyM?.let { String.format(Locale.ROOT, " (±%.0f m)", it) }.orEmpty()
        return LogData(
            timeUnixNano = fix.timeMs * 1_000_000,
            observedTimeUnixNano = nowMs * 1_000_000,
            severity = Severity.INFO,
            body = "Location $lat,$lon$accuracy",
            attributes = attrs,
        )
    }

    fun round(value: Double, precision: LocationPrecision): Double {
        val decimals = precision.decimals ?: return value
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()
    }
}
