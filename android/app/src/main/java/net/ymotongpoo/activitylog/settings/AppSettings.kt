package net.ymotongpoo.activitylog.settings

import android.content.Context
import androidx.core.content.edit
import net.ymotongpoo.activitylog.collect.PrivacyPolicy
import net.ymotongpoo.activitylog.collect.UrlMode
import net.ymotongpoo.activitylog.location.LocationInterval
import net.ymotongpoo.activitylog.location.LocationPrecision

data class AppSettings(
    val endpoint: String,
    val instanceId: String,
    val token: String,
    val deviceNameOverride: String,
    val excludedPackages: String,
    val excludedDomains: String,
    val urlMode: UrlMode,
    val locationEnabled: Boolean = false,
    val locationPrecision: LocationPrecision = LocationPrecision.FULL,
    val locationInterval: LocationInterval = LocationInterval.MINUTE,
) {
    val isExportConfigured: Boolean
        get() = missingExportSettings().isEmpty()

    /** Names of the export settings that are not set yet. */
    fun missingExportSettings(): List<String> = buildList {
        if (endpoint.isBlank()) add("endpoint")
        if (instanceId.isBlank()) add("instance ID")
        if (token.isBlank()) add("token")
    }

    fun privacyPolicy(): PrivacyPolicy = PrivacyPolicy(
        excludedPackages = PrivacyPolicy.parseList(excludedPackages),
        excludedDomains = PrivacyPolicy.parseList(excludedDomains),
        urlMode = urlMode,
    )
}

/** SharedPreferences-backed settings; the token is stored encrypted by [TokenCipher]. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        endpoint = prefs.getString(KEY_ENDPOINT, null).orEmpty(),
        instanceId = prefs.getString(KEY_INSTANCE_ID, null).orEmpty(),
        token = prefs.getString(KEY_TOKEN, null)?.let(TokenCipher::decrypt).orEmpty(),
        deviceNameOverride = prefs.getString(KEY_DEVICE_NAME, null).orEmpty(),
        excludedPackages = prefs.getString(KEY_EXCLUDED_PACKAGES, null).orEmpty(),
        excludedDomains = prefs.getString(KEY_EXCLUDED_DOMAINS, null).orEmpty(),
        urlMode = UrlMode.fromId(prefs.getString(KEY_URL_MODE, null)),
        locationEnabled = prefs.getBoolean(KEY_LOCATION_ENABLED, false),
        locationPrecision = LocationPrecision.fromId(prefs.getString(KEY_LOCATION_PRECISION, null)),
        locationInterval = LocationInterval.fromId(prefs.getString(KEY_LOCATION_INTERVAL, null)),
    )

    fun hasToken(): Boolean = prefs.contains(KEY_TOKEN)

    /** True when a token is stored but cannot be decrypted, e.g. after the keystore key was lost. */
    fun tokenUnreadable(): Boolean =
        prefs.getString(KEY_TOKEN, null)?.let { TokenCipher.decrypt(it) == null } ?: false

    /** Saves everything except the token; pass a non-null [newToken] to replace it. */
    fun save(settings: AppSettings, newToken: String?) {
        val encrypted = newToken?.trim()?.takeIf { it.isNotEmpty() }?.let(TokenCipher::encrypt)
        prefs.edit {
            putString(KEY_ENDPOINT, settings.endpoint.trim())
            putString(KEY_INSTANCE_ID, settings.instanceId.trim())
            putString(KEY_DEVICE_NAME, settings.deviceNameOverride.trim())
            putString(KEY_EXCLUDED_PACKAGES, settings.excludedPackages)
            putString(KEY_EXCLUDED_DOMAINS, settings.excludedDomains)
            putString(KEY_URL_MODE, settings.urlMode.id)
            putBoolean(KEY_LOCATION_ENABLED, settings.locationEnabled)
            putString(KEY_LOCATION_PRECISION, settings.locationPrecision.id)
            putString(KEY_LOCATION_INTERVAL, settings.locationInterval.id)
            if (encrypted != null) putString(KEY_TOKEN, encrypted)
        }
    }

    fun clearToken() {
        prefs.edit { remove(KEY_TOKEN) }
    }

    private companion object {
        const val PREFS = "settings"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_INSTANCE_ID = "instance_id"
        const val KEY_TOKEN = "token_encrypted"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
        const val KEY_EXCLUDED_DOMAINS = "excluded_domains"
        const val KEY_URL_MODE = "url_mode"
        const val KEY_LOCATION_ENABLED = "location_enabled"
        const val KEY_LOCATION_PRECISION = "location_precision"
        const val KEY_LOCATION_INTERVAL = "location_interval"
    }
}
