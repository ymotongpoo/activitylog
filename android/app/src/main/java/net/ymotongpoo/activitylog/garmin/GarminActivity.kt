package net.ymotongpoo.activitylog.garmin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.ymotongpoo.activitylog.R
import net.ymotongpoo.activitylog.BuildConfig
import net.ymotongpoo.activitylog.settings.StatusStore
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Garmin settings and received measurements within Activitylog. */
class GarminActivity : Activity() {
    private lateinit var store: GarminStore
    private lateinit var status: TextView
    private lateinit var history: TextView
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { refresh() } }
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            status.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GarminStore(this)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            text = value
            textSize = size
            setTextIsSelectable(true)
            content.addView(this)
        }
        fun button(label: Int, action: () -> Unit) {
            content.addView(Button(this).apply {
                setText(label)
                setOnClickListener { action() }
            })
        }
        text(getString(R.string.garmin_title), 22f)
        text(getString(if (BuildConfig.GARMIN_EXPORT_ENABLED) R.string.garmin_help else R.string.garmin_help_local))
        button(R.string.garmin_start) { startReceiver() }
        button(R.string.garmin_stop) {
            store.enabled = false
            stopService(Intent(this, GarminReceiverService::class.java))
            store.status("Receiver stopped")
        }
        button(R.string.garmin_back) { finish() }
        status = text("")
        text(getString(R.string.garmin_recent), 18f)
        history = text("", 14f)
        scroll.addView(content)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun startReceiver() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !GarminReceiverService.hasConnectionPermission(this@GarminActivity)) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        startGranted()
    }

    private fun startGranted() {
        if (!GarminReceiverService.hasConnectionPermission(this)) {
            Toast.makeText(this, R.string.garmin_permission_needed, Toast.LENGTH_LONG).show()
            return
        }
        store.enabled = true
        try {
            startForegroundService(Intent(this, GarminReceiverService::class.java))
        } catch (e: RuntimeException) {
            store.enabled = false
            store.status("Could not start receiver: ${e.javaClass.simpleName}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) startGranted()
    }

    override fun onResume() {
        super.onResume()
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        StatusStore(this).prefs.registerOnSharedPreferenceChangeListener(listener)
        GarminReceiverService.sync(this)
        tick.run()
    }

    override fun onPause() {
        status.removeCallbacks(tick)
        store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        StatusStore(this).prefs.unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    private fun refresh() {
        val now = System.currentTimeMillis() / 1000
        fun date(seconds: Long): String = if (seconds == 0L) getString(R.string.never)
            else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(seconds * 1000))
        fun metric(spec: GarminMetric, sample: GarminProtocol.Value?): String {
            if (sample == null) return "${spec.label}: unavailable"
            val value = if (spec.integer) sample.value.toLong().toString()
                else String.format(Locale.ROOT, "%.2f", sample.value.toDouble()).trimEnd('0').trimEnd('.')
            val timeLabel = if (spec.measured) "measured" else "observed"
            return "${spec.label}: $value${spec.displayUnit} · $timeLabel ${date(sample.time)} · age ${maxOf(0, now - sample.time)} s"
        }
        val rows = runCatching { store.history() }
        status.text = getString(
            R.string.garmin_status, store.status, store.packetCount, date(store.lastReceivedAt), rows.getOrDefault(emptyList()).size,
        )
        if (BuildConfig.GARMIN_EXPORT_ENABLED) {
            status.append("\n\n${getString(R.string.garmin_export_status, store.pending().size, StatusStore(this).lastSendResult)}")
        }
        history.text = rows.fold(
            onSuccess = { entries ->
                if (entries.isEmpty()) getString(R.string.garmin_no_data)
                else entries.takeLast(20).asReversed().joinToString("\n\n") { receipt ->
                    "${receipt.deviceName}\nCollected: ${date(receipt.sample.capturedAt)}\n" +
                        "Received: ${date(receipt.receivedAt)}\n" +
                        GarminMetric.entries.joinToString("\n") { metric(it, it.reading(receipt.sample)) }
                }
            },
            onFailure = { "Cannot read saved samples: ${it.javaClass.simpleName}" },
        )
    }

    companion object { private const val REQUEST_PERMISSIONS = 41 }
}
