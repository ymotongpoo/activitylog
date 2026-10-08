package net.ymotongpoo.activitylog.collect

import android.content.Context
import android.content.pm.PackageManager

/** Resolves labels and categories through [PackageManager]. */
class PackageAppInfoResolver(context: Context) : AppInfoResolver {
    private val pm = context.packageManager

    override fun resolve(packageName: String): AppInfo = try {
        val ai = pm.getApplicationInfo(packageName, 0)
        val label = pm.getApplicationLabel(ai).toString().ifBlank { packageName }
        AppInfo(label, AppCategories.fromAndroidCategory(ai.category))
    } catch (_: PackageManager.NameNotFoundException) {
        // Uninstalled since the event was recorded, or hidden by package visibility.
        AppInfo(packageName, AppCategories.UNCATEGORIZED)
    }
}
