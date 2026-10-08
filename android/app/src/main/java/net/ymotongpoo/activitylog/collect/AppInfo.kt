package net.ymotongpoo.activitylog.collect

data class AppInfo(val label: String, val category: String)

fun interface AppInfoResolver {
    fun resolve(packageName: String): AppInfo
}

/** Maps `ApplicationInfo.category` values to `activity.category`. */
object AppCategories {
    const val UNCATEGORIZED = "Uncategorized"

    // Values of ApplicationInfo.CATEGORY_* (stable platform constants).
    private val names = mapOf(
        0 to "game",
        1 to "audio",
        2 to "video",
        3 to "image",
        4 to "social",
        5 to "news",
        6 to "maps",
        7 to "productivity",
        8 to "accessibility",
    )

    fun fromAndroidCategory(category: Int): String =
        names[category]?.let { "Android/$it" } ?: UNCATEGORIZED
}
