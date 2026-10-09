package net.ymotongpoo.activitylog.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.ymotongpoo.activitylog.R
import net.ymotongpoo.activitylog.collect.UrlMode
import net.ymotongpoo.activitylog.location.LocationCollector
import net.ymotongpoo.activitylog.location.LocationInterval
import net.ymotongpoo.activitylog.location.LocationPrecision
import net.ymotongpoo.activitylog.location.LocationService
import net.ymotongpoo.activitylog.otlp.DeviceInfo
import net.ymotongpoo.activitylog.otlp.Spool
import net.ymotongpoo.activitylog.settings.AppSettings
import net.ymotongpoo.activitylog.settings.Permissions
import net.ymotongpoo.activitylog.settings.SettingsStore
import net.ymotongpoo.activitylog.settings.StatusStore
import net.ymotongpoo.activitylog.work.Scheduler
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Single settings screen built from plain framework views. */
class MainActivity : Activity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var statusStore: StatusStore

    private lateinit var endpoint: EditText
    private lateinit var instanceId: EditText
    private lateinit var token: EditText
    private lateinit var deviceName: EditText
    private lateinit var excludedPackages: EditText
    private lateinit var excludedDomains: EditText
    private lateinit var urlMode: RadioGroup
    private lateinit var locationEnabled: CheckBox
    private lateinit var locationPrecision: RadioGroup
    private lateinit var locationInterval: RadioGroup
    private lateinit var permissionStatus: TextView
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView

    // Kept as a field: SharedPreferences holds listeners weakly.
    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshStatus() }

    private val intervalIds = mapOf(
        LocationInterval.MINUTE to R.id.location_interval_minute,
        LocationInterval.JOB to R.id.location_interval_job,
    )

    private val precisionIds = mapOf(
        LocationPrecision.FULL to R.id.location_precision_full,
        LocationPrecision.M100 to R.id.location_precision_100m,
        LocationPrecision.KM1 to R.id.location_precision_1km,
    )

    private val urlModeIds = mapOf(
        UrlMode.FULL to R.id.url_mode_full,
        UrlMode.PATH to R.id.url_mode_path,
        UrlMode.DOMAIN to R.id.url_mode_domain,
        UrlMode.NONE to R.id.url_mode_none,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settingsStore = SettingsStore(this)
        statusStore = StatusStore(this)

        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        endpoint = findViewById(R.id.endpoint)
        instanceId = findViewById(R.id.instance_id)
        token = findViewById(R.id.token)
        deviceName = findViewById(R.id.device_name)
        excludedPackages = findViewById(R.id.excluded_packages)
        excludedDomains = findViewById(R.id.excluded_domains)
        urlMode = findViewById(R.id.url_mode)
        locationEnabled = findViewById(R.id.location_enabled)
        locationPrecision = findViewById(R.id.location_precision)
        locationInterval = findViewById(R.id.location_interval)
        permissionStatus = findViewById(R.id.permission_status)
        status = findViewById(R.id.status)
        diagnostics = findViewById(R.id.diagnostics)

        if (savedInstanceState == null) loadSettings()
        validate()

        findViewById<Button>(R.id.save).setOnClickListener {
            saveSettings()
            val complete = validate()
            // Send what was queued while the settings were missing.
            if (complete) Scheduler.runNow(this)
            Toast.makeText(this, if (complete) R.string.saved else R.string.saved_incomplete, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.clear_token).setOnClickListener {
            settingsStore.clearToken()
            token.text.clear()
            updateTokenHint()
        }
        findViewById<Button>(R.id.open_usage_access).setOnClickListener {
            open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.open_app_info).setOnClickListener {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
        findViewById<Button>(R.id.open_location).setOnClickListener { requestLocationPermission() }
        locationInterval.setOnCheckedChangeListener { _, _ -> saveSettings() }
        locationEnabled.setOnCheckedChangeListener { _, checked ->
            saveSettings()
            if (checked && !Permissions.hasBackgroundLocation(this)) requestLocationPermission()
        }
        findViewById<Button>(R.id.send_now).setOnClickListener {
            saveSettings()
            validate()
            Scheduler.runNow(this)
            Toast.makeText(this, R.string.send_requested, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        statusStore.prefs.registerOnSharedPreferenceChangeListener(statusListener)
        refreshPermissions()
        refreshStatus()
    }

    override fun onPause() {
        // Opening the system settings to grant a permission can kill this
        // process; save what was typed so that it is not only kept in the
        // restored view state.
        saveSettings()
        statusStore.prefs.unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onPause()
    }

    private fun loadSettings() {
        val s = settingsStore.load()
        endpoint.setText(s.endpoint)
        instanceId.setText(s.instanceId)
        deviceName.setText(s.deviceNameOverride)
        excludedPackages.setText(s.excludedPackages)
        excludedDomains.setText(s.excludedDomains)
        urlMode.check(urlModeIds.getValue(s.urlMode))
        locationEnabled.isChecked = s.locationEnabled
        locationPrecision.check(precisionIds.getValue(s.locationPrecision))
        locationInterval.check(intervalIds.getValue(s.locationInterval))
        updateTokenHint()
    }

    private fun saveSettings() {
        val mode = urlModeIds.entries.firstOrNull { it.value == urlMode.checkedRadioButtonId }?.key ?: UrlMode.PATH
        val newToken = token.text.toString().trim().ifEmpty { null }
        settingsStore.save(
            AppSettings(
                endpoint = endpoint.text.toString(),
                instanceId = instanceId.text.toString(),
                token = "",
                deviceNameOverride = deviceName.text.toString(),
                excludedPackages = excludedPackages.text.toString(),
                excludedDomains = excludedDomains.text.toString(),
                urlMode = mode,
                locationEnabled = locationEnabled.isChecked,
                locationPrecision = precisionIds.entries
                    .firstOrNull { it.value == locationPrecision.checkedRadioButtonId }?.key ?: LocationPrecision.FULL,
                locationInterval = intervalIds.entries
                    .firstOrNull { it.value == locationInterval.checkedRadioButtonId }?.key ?: LocationInterval.MINUTE,
            ),
            newToken,
        )
        val collector = LocationCollector(this)
        if (locationEnabled.isChecked) collector.registerPassive() else collector.unregisterPassive()
        // The activity is in the foreground here, so the service may be started.
        LocationService.sync(this)
        token.text.clear()
        updateTokenHint()
        refreshStatus()
    }

    /** Marks missing or malformed export settings on their fields; returns true when all are set. */
    private fun validate(): Boolean {
        val s = settingsStore.load()
        endpoint.error = when {
            s.endpoint.isBlank() -> getString(R.string.error_required)
            !s.endpoint.startsWith("https://") && !s.endpoint.startsWith("http://") ->
                getString(R.string.error_endpoint_scheme)
            else -> null
        }
        instanceId.error = if (s.instanceId.isBlank()) getString(R.string.error_required) else null
        token.error = if (s.token.isBlank()) getString(R.string.error_required) else null
        return endpoint.error == null && instanceId.error == null && token.error == null
    }

    private fun updateTokenHint() {
        token.setHint(if (settingsStore.hasToken()) R.string.hint_token_saved else R.string.hint_token_empty)
    }

    /**
     * Asks for location in the two steps Android requires: while-in-use first,
     * then "Allow all the time", which Android 11+ grants only on the app's
     * permission page that this request opens.
     */
    private fun requestLocationPermission() {
        when {
            !Permissions.hasForegroundLocation(this) -> requestPermissions(
                // The notification of the minutely location service needs POST_NOTIFICATIONS to be visible.
                buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray(),
                REQUEST_LOCATION,
            )
            !Permissions.hasBackgroundLocation(this) -> requestPermissions(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQUEST_BACKGROUND_LOCATION,
            )
            else -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
        if (requestCode == REQUEST_LOCATION && Permissions.hasForegroundLocation(this) &&
            !Permissions.hasBackgroundLocation(this)
        ) {
            requestLocationPermission()
        }
        if (Permissions.hasBackgroundLocation(this) && locationEnabled.isChecked) {
            LocationCollector(this).registerPassive()
            LocationService.sync(this)
        }
    }

    private fun refreshPermissions() {
        val yes = getString(R.string.granted)
        val no = getString(R.string.not_granted)
        permissionStatus.text = getString(
            R.string.permission_status,
            if (Permissions.hasUsageAccess(this)) yes else no,
            if (Permissions.isAccessibilityEnabled(this)) yes else no,
            when {
                Permissions.hasBackgroundLocation(this) && Permissions.hasPreciseLocation(this) ->
                    getString(R.string.location_all_the_time_precise)
                Permissions.hasBackgroundLocation(this) -> getString(R.string.location_all_the_time_approximate)
                Permissions.hasForegroundLocation(this) -> getString(R.string.location_while_in_use)
                else -> no
            },
        )
        deviceName.hint = getString(R.string.hint_device_name, DeviceInfo.defaultDeviceName(this))
    }

    private fun refreshStatus() {
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        fun time(ms: Long) = if (ms <= 0) getString(R.string.never) else fmt.format(Date(ms))
        val spool = Spool(File(filesDir, "spool"))
        status.text = getString(
            R.string.status_text,
            DeviceInfo.deviceName(this, settingsStore.load().deviceNameOverride),
            time(statusStore.lastRunMs),
            statusStore.lastRunSummary,
            time(statusStore.lastSendMs),
            statusStore.lastSendResult,
            spool.count(),
            (spool.totalBytes() / 1024).toInt(),
        )
        val recent = statusStore.recentDiagnostics()
        diagnostics.text = if (recent.isEmpty()) {
            ""
        } else {
            buildString {
                append(getString(R.string.diagnostics_header))
                for (d in recent) {
                    append('\n').append(fmt.format(Date(d.timeMs))).append(' ')
                        .append(d.severity.text).append(' ').append(d.message)
                }
            }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private companion object {
        const val REQUEST_LOCATION = 1
        const val REQUEST_BACKGROUND_LOCATION = 2
    }
}
