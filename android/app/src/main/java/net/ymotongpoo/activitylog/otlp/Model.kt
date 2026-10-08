package net.ymotongpoo.activitylog.otlp

/** A typed OTLP attribute value. */
sealed interface AttributeValue {
    data class Str(val value: String) : AttributeValue
    data class IntVal(val value: Long) : AttributeValue
    data class BoolVal(val value: Boolean) : AttributeValue
    data class DoubleVal(val value: Double) : AttributeValue
}

data class Attribute(val key: String, val value: AttributeValue)

fun attr(key: String, value: String) = Attribute(key, AttributeValue.Str(value))
fun attr(key: String, value: Long) = Attribute(key, AttributeValue.IntVal(value))
fun attr(key: String, value: Int) = Attribute(key, AttributeValue.IntVal(value.toLong()))
fun attr(key: String, value: Boolean) = Attribute(key, AttributeValue.BoolVal(value))
fun attr(key: String, value: Double) = Attribute(key, AttributeValue.DoubleVal(value))

/** Instrumentation scope shared by every signal. */
data class Scope(val name: String, val version: String)

data class SpanLink(val traceId: String, val spanId: String)

data class SpanData(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val name: String,
    val startTimeUnixNano: Long,
    val endTimeUnixNano: Long,
    val attributes: List<Attribute>,
    val links: List<SpanLink> = emptyList(),
)

enum class Severity(val number: Int, val text: String) {
    INFO(9, "INFO"),
    WARN(13, "WARN"),
    ERROR(17, "ERROR"),
}

data class LogData(
    val timeUnixNano: Long,
    val observedTimeUnixNano: Long,
    val severity: Severity,
    val body: String,
    val attributes: List<Attribute>,
    val traceId: String? = null,
    val spanId: String? = null,
)

/** One data point of a cumulative monotonic Sum. */
data class SumPoint(
    val attributes: List<Attribute>,
    val startTimeUnixNano: Long,
    val timeUnixNano: Long,
    val value: Double,
)

data class SumMetric(
    val name: String,
    val description: String,
    val unit: String,
    /** When true the points are encoded as `asInt` instead of `asDouble`. */
    val integer: Boolean,
    val points: List<SumPoint>,
)

const val NANOS_PER_MILLI = 1_000_000L

fun Long.millisToNanos(): Long = this * NANOS_PER_MILLI
