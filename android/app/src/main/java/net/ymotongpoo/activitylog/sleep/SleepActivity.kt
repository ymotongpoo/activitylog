package net.ymotongpoo.activitylog.sleep

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ymotongpoo.activitylog.R
import net.ymotongpoo.activitylog.settings.StatusStore
import java.text.DateFormat
import java.util.Date

/** User-initiated, read-only Health Connect onboarding and import controls. */
class SleepActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var history: TextView
    private var importing = false
    private val permissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (SleepSync.readPermission in granted) {
            SleepStore(this).enabled = true
            SleepScheduler.ensure(this)
            importNow()
        } else {
            SleepStore(this).status("Sleep access was not granted.")
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setTextIsSelectable(true); content.addView(this)
        }
        fun button(label: Int, action: () -> Unit) = content.addView(Button(this).apply {
            setText(label); setOnClickListener { action() }
        })
        text(getString(R.string.sleep_title), 22f)
        text(getString(R.string.sleep_help))
        button(R.string.sleep_enable) { enable() }
        button(R.string.sleep_import_now) { importNow() }
        button(R.string.sleep_stop) {
            SleepStore(this).apply { enabled = false; status("Sleep import is off") }
            SleepScheduler.ensure(this)
            refresh()
        }
        button(R.string.sleep_settings) {
            runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                .onFailure { SleepStore(this).status("Health Connect settings are unavailable."); refresh() }
        }
        button(R.string.sleep_privacy_title) { startActivity(Intent(this, SleepPrivacyActivity::class.java)) }
        button(R.string.garmin_back) { finish() }
        status = text("")
        history = text("", 14f)
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun enable() {
        if (!SleepSync.available(this)) {
            SleepStore(this).status("Health Connect is unavailable. Install or update it using Android settings.")
            refresh()
            return
        }
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@SleepActivity)
                val requested = buildSet {
                    add(SleepSync.readPermission)
                    if (SleepSync.backgroundAvailable(client)) add(SleepSync.backgroundPermission)
                }
                val granted = client.permissionController.getGrantedPermissions()
                if (granted.containsAll(requested)) {
                    SleepStore(this@SleepActivity).enabled = true
                    SleepScheduler.ensure(this@SleepActivity)
                    importNow()
                } else permissions.launch(requested)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                SleepStore(this@SleepActivity).status("Could not request sleep access (${e.javaClass.simpleName}).")
                refresh()
            }
        }
    }

    private fun importNow() {
        if (importing) return
        val store = SleepStore(this)
        if (!store.enabled) { store.status("Use Enable sleep import to allow access first."); refresh(); return }
        importing = true
        status.text = getString(R.string.sleep_importing)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SleepSync.sync(this@SleepActivity, background = false) }
            } catch (e: CancellationException) { throw e
            } catch (e: SecurityException) {
                store.status("Sleep access changed. Use Enable sleep import to review permissions.")
            } catch (e: Exception) {
                store.status("Sleep import failed (${e.javaClass.simpleName}). No partial read was saved.")
            } finally {
                importing = false
                refresh()
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun refresh() {
        if (!::status.isInitialized || importing) return
        val store = SleepStore(this)
        fun date(ms: Long): String = if (ms == 0L) getString(R.string.never)
            else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
        status.text = "${if (store.enabled) "Enabled" else "Off"}\n${store.status}\nLast import: ${date(store.lastSyncMs)}\n" +
            "Activitylog upload: ${StatusStore(this).lastSendResult}\n"
        scope.launch {
            val availability = runCatching {
                if (!SleepSync.available(this@SleepActivity)) return@runCatching "Health Connect unavailable"
                val client = HealthConnectClient.getOrCreate(this@SleepActivity)
                val granted = client.permissionController.getGrantedPermissions()
                "Sleep access: ${SleepSync.readPermission in granted}; background access: ${SleepSync.backgroundPermission in granted}"
            }.getOrDefault("Permission status unavailable")
            if (!importing) status.append("\n$availability")
        }
        history.text = runCatching {
            val sessions = store.sessions().values.sortedByDescending { it.endMs }.take(7)
            if (sessions.isEmpty()) getString(R.string.sleep_no_records) else sessions.joinToString("\n\n") { session ->
                buildString {
                    append("${date(session.startMs)} → ${date(session.endMs)}\n")
                    for (stage in SleepStage.entries) {
                        val ms = session.stages.filter { it.stage == stage }.sumOf { it.endMs - it.startMs }
                        if (ms > 0) append("${stage.label}: ${ms / 60000} min\n")
                    }
                    append("Intervals:\n")
                    session.stages.forEach { append("${date(it.startMs)} – ${date(it.endMs)}: ${it.stage.label}\n") }
                }.trimEnd()
            }
        }.getOrElse { "Cannot read saved sleep sessions (${it.javaClass.simpleName})" }
    }
}
