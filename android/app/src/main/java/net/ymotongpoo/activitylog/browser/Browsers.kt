package net.ymotongpoo.activitylog.browser

/** Supported browser packages and their `activity.browser.name` values. */
object Browsers {
    val names: Map<String, String> = linkedMapOf(
        "com.android.chrome" to "chrome",
        "com.chrome.beta" to "chrome",
        "com.chrome.dev" to "chrome",
        "com.brave.browser" to "brave",
        "com.microsoft.emmx" to "edge",
        "com.vivaldi.browser" to "vivaldi",
        "org.chromium.chrome" to "chromium",
    )

    fun isBrowser(packageName: String): Boolean = packageName in names

    fun browserName(packageName: String): String? = names[packageName]
}
