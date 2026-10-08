package net.ymotongpoo.activitylog.collect

/** An open root `active` span. */
data class OpenRoot(
    val traceId: String,
    val spanId: String,
    val startMs: Long,
    var creditedUntilMs: Long,
    var switches: Int = 0,
    /** Previous root when this root was created by the max-duration split. */
    val linkTraceId: String? = null,
    val linkSpanId: String? = null,
)

/** An open app span; child of [OpenRoot]. */
data class OpenApp(
    val spanId: String,
    val packageName: String,
    val startMs: Long,
    var creditedUntilMs: Long,
)

/** An open `browser.tab` span; child of [OpenApp]. [url] is the normalized full URL. */
data class OpenTab(
    val spanId: String,
    val url: String,
    val startMs: Long,
    var creditedUntilMs: Long,
)

data class PendingObservation(val timeMs: Long, val text: String)

/**
 * Everything the tracker needs to continue from one run to the next. Open intervals
 * are carried over with their IDs and start times instead of being emitted early.
 */
data class TrackerState(
    /** Usage events before this time have been processed. */
    var checkpointMs: Long,
    /** Screen state; null when unknown (first run, after boot). */
    var interactive: Boolean? = null,
    /** Keyguard state; null when unknown. */
    var locked: Boolean? = null,
    /** Whether any keyguard event was ever seen (devices without a lock screen never report one). */
    var keyguardSeen: Boolean = false,
    var lastScreenOnMs: Long = 0,
    var foregroundPackage: String? = null,
    var foregroundResumedMs: Long = 0,
    var root: OpenRoot? = null,
    var app: OpenApp? = null,
    var tab: OpenTab? = null,
    /** Latest observation per browser package that did not fall inside an app span yet. */
    val pending: MutableMap<String, PendingObservation> = linkedMapOf(),
)
