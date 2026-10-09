package net.ymotongpoo.activitylog.settings

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import net.ymotongpoo.activitylog.browser.UrlCaptureService

object Permissions {
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        // checkOpNoThrow(String, int, String) exists since API 19 and is the
        // non-deprecated form again as of API 36.
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun hasForegroundLocation(context: Context): Boolean =
        granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPreciseLocation(context: Context): Boolean =
        granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)

    /** "Allow all the time", which the periodic collection job needs. */
    fun hasBackgroundLocation(context: Context): Boolean =
        hasForegroundLocation(context) && granted(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = ComponentName(context, UrlCaptureService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (ComponentName.unflattenFromString(entry) == me) return true
        }
        return false
    }
}
