package net.ymotongpoo.activitylog.collect

/** The subset of `UsageEvents.Event` types the tracker understands. */
enum class UsageEventKind {
    SCREEN_INTERACTIVE,
    SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    ACTIVITY_RESUMED,
    DEVICE_SHUTDOWN,
    DEVICE_STARTUP,
}

data class UsageEvent(
    val timeMs: Long,
    val kind: UsageEventKind,
    val packageName: String? = null,
)
