package net.ymotongpoo.activitylog.otlp

/** OTLP signal types and their HTTP paths relative to the endpoint. */
enum class Signal(val id: String, val path: String) {
    TRACES("traces", "v1/traces"),
    METRICS("metrics", "v1/metrics"),
    LOGS("logs", "v1/logs");

    companion object {
        fun fromId(id: String): Signal? = entries.firstOrNull { it.id == id }
    }
}
