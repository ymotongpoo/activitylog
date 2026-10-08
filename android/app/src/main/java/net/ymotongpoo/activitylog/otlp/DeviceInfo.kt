package net.ymotongpoo.activitylog.otlp

import android.content.Context
import android.os.Build
import android.provider.Settings
import net.ymotongpoo.activitylog.BuildConfig

object DeviceInfo {
    val scope = Scope("activitylog-android", BuildConfig.VERSION_NAME)

    /** User-visible device name, falling back to the model name. */
    fun defaultDeviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL

    fun deviceName(context: Context, override: String): String =
        override.trim().ifEmpty { defaultDeviceName(context) }

    fun resource(context: Context, deviceNameOverride: String): List<Attribute> {
        val name = deviceName(context, deviceNameOverride)
        return listOf(
            attr("service.name", "activitylog-android"),
            attr("service.namespace", "activitylog"),
            attr("service.version", BuildConfig.VERSION_NAME),
            attr("service.instance.id", name),
            attr("host.name", name),
            attr("os.type", "linux"),
            attr("os.name", "Android"),
            attr("os.version", Build.VERSION.RELEASE),
            attr("device.model.identifier", Build.MODEL),
            attr("device.manufacturer", Build.MANUFACTURER),
        )
    }
}
