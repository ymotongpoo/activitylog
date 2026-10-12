package net.ymotongpoo.activitylog.metrics

/** Metric and attribute names shared with the desktop agent and the dashboards. */
object MetricNames {
    const val ACTIVITY_TIME = "activity.time"
    const val APP_TIME = "activity.app.time"
    const val CATEGORY_TIME = "activity.category.time"
    const val BROWSER_DOMAIN_TIME = "activity.browser.domain.time"
    const val WIFI_DATA_USAGE = "activity.network.wifi.usage"
    const val MOBILE_DATA_USAGE = "activity.network.mobile.usage"
    const val DEVICE_UNLOCKS = "activity.device.unlocks"

    data class Info(val description: String, val unit: String, val integer: Boolean)

    val info: Map<String, Info> = mapOf(
        ACTIVITY_TIME to Info("Time spent per activity state", "s", false),
        APP_TIME to Info("Time spent in the foreground per application", "s", false),
        CATEGORY_TIME to Info("Time spent per application category", "s", false),
        BROWSER_DOMAIN_TIME to Info("Time spent per browser domain", "s", false),
        WIFI_DATA_USAGE to Info("Wi-Fi data transferred", "By", true),
        MOBILE_DATA_USAGE to Info("Mobile data transferred", "By", true),
        DEVICE_UNLOCKS to Info("Number of device unlocks", "", true),
    )
}
