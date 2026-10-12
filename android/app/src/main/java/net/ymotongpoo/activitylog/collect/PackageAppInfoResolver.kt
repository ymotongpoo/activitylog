package net.ymotongpoo.activitylog.collect

import android.content.Context
import android.content.pm.PackageManager
import net.ymotongpoo.activitylog.settings.CategorySettings
import net.ymotongpoo.activitylog.settings.CategoryStore

/** Resolves labels and categories through [PackageManager]. */
class PackageAppInfoResolver(
    context: Context,
    private val categories: CategorySettings = CategoryStore(context).load(),
) : AppInfoResolver {
    private val pm = context.packageManager

    override fun resolve(packageName: String): AppInfo = try {
        val ai = pm.getApplicationInfo(packageName, 0)
        val label = pm.getApplicationLabel(ai).toString().ifBlank { packageName }
        AppInfo(label, categories.resolve(packageName, AppCategories.fromAndroidCategory(ai.category)))
    } catch (_: PackageManager.NameNotFoundException) {
        // Uninstalled since the event was recorded, or hidden by package visibility.
        AppInfo(packageName, categories.resolve(packageName, AppCategories.UNCATEGORIZED))
    }
}
