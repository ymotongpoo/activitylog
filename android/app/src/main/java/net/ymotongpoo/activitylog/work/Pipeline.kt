package net.ymotongpoo.activitylog.work

import android.app.KeyguardManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import net.ymotongpoo.activitylog.BuildConfig
import net.ymotongpoo.activitylog.browser.ObservationQueue
import net.ymotongpoo.activitylog.collect.ActivityEngine
import net.ymotongpoo.activitylog.collect.PackageAppInfoResolver
import net.ymotongpoo.activitylog.collect.PersistedState
import net.ymotongpoo.activitylog.collect.RandomIdGenerator
import net.ymotongpoo.activitylog.collect.UsageEventSource
import net.ymotongpoo.activitylog.otlp.DeviceInfo
import net.ymotongpoo.activitylog.otlp.FlushResult
import net.ymotongpoo.activitylog.otlp.Flusher
import net.ymotongpoo.activitylog.otlp.Gzip
import net.ymotongpoo.activitylog.otlp.LogData
import net.ymotongpoo.activitylog.otlp.OtlpJsonEncoder
import net.ymotongpoo.activitylog.otlp.OtlpSender
import net.ymotongpoo.activitylog.otlp.SendResult
import net.ymotongpoo.activitylog.otlp.Severity
import net.ymotongpoo.activitylog.otlp.Signal
import net.ymotongpoo.activitylog.otlp.SpanData
import net.ymotongpoo.activitylog.otlp.Spool
import net.ymotongpoo.activitylog.otlp.attr
import net.ymotongpoo.activitylog.otlp.millisToNanos
import net.ymotongpoo.activitylog.settings.AppSettings
import net.ymotongpoo.activitylog.settings.Permissions
import net.ymotongpoo.activitylog.location.LocationCollector
import net.ymotongpoo.activitylog.location.LocationLogs
import net.ymotongpoo.activitylog.location.LocationQueue
import net.ymotongpoo.activitylog.location.LocationService
import net.ymotongpoo.activitylog.settings.SettingsStore
import net.ymotongpoo.activitylog.settings.StatusStore
import org.json.JSONException
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * One collection run: read usage events and browser observations since the checkpoint,
 * turn them into telemetry, put the payloads into the spool, persist the state and
 * then try to send the spool. Collection never needs the network; offline runs just
 * leave payloads in the spool.
 */
class Pipeline(context: Context) {
    private val ctx = context.applicationContext
    private val settingsStore = SettingsStore(ctx)
    private val status = StatusStore(ctx)
    private val stateStore = StateStore(File(ctx.filesDir, "state.json"))
    private val queue = ObservationQueue(ctx.filesDir)
    private val spool = Spool(File(ctx.filesDir, "spool"))
    private val locationQueue = LocationQueue(ctx.filesDir)

    fun collectAndSend(): FlushResult = synchronized(LOCK) {
        val settings = settingsStore.load()
        try {
            collect(settings)
        } catch (e: Exception) {
            Log.e(TAG, "collection failed", e)
            status.diagnostic(Severity.ERROR, "Collection failed: ${e.javaClass.simpleName}: ${e.message}")
            status.recordRun(System.currentTimeMillis(), "failed: ${e.message}")
        }
        flushLocked(settings)
    }

    fun flush(): FlushResult = synchronized(LOCK) { flushLocked(settingsStore.load()) }

    private fun collect(settings: AppSettings) {
        val observations = queue.beginDrain()
        val nowMs = System.currentTimeMillis()
        val state = loadState(nowMs)
        val logs = mutableListOf<LogData>()

        agentLogs(state, nowMs, logs)

        // Location is independent of Usage Access. Fixes queued while the
        // feature was on but that arrive after it was turned off are dropped.
        if (settings.locationEnabled) {
            val collector = LocationCollector(ctx)
            collector.registerPassive()
            LocationService.sync(ctx)
            // With the minutely foreground service running, its fixes are enough.
            if (!LocationService.running) {
                collector.currentFix(LOCATION_TIMEOUT_MS)?.let { locationQueue.append(it) }
            }
        }
        val fixes = locationQueue.beginDrain()
        val locationLogs = if (settings.locationEnabled) {
            LocationLogs.build(fixes, settings.locationPrecision, nowMs)
        } else {
            emptyList()
        }
        logs += locationLogs

        var spans: List<SpanData> = emptyList()
        val usageGranted = state.agent.usageAccessGranted == true
        if (usageGranted) {
            val events = UsageEventSource(ctx).query(state.tracker.checkpointMs, nowMs)
            val engine = ActivityEngine(PackageAppInfoResolver(ctx), settings.privacyPolicy(), RandomIdGenerator())
            val out = engine.process(state.tracker, state.metrics, events, observations, nowMs)
            spans = out.spans
            logs += out.logs
        }

        val diagnostics = status.pendingDiagnostics()
        for (d in diagnostics) {
            logs += LogData(
                timeUnixNano = d.timeMs.millisToNanos(),
                observedTimeUnixNano = nowMs.millisToNanos(),
                severity = d.severity,
                body = d.message,
                attributes = listOf(attr("event.name", "agent.diagnostic")),
            )
        }

        val resource = DeviceInfo.resource(ctx, settings.deviceNameOverride)
        val scope = DeviceInfo.scope
        val dropped = mutableListOf<File>()
        for (chunk in spans.chunked(MAX_ITEMS_PER_REQUEST)) {
            dropped += spool.put(Signal.TRACES, Gzip.compress(OtlpJsonEncoder.encodeTraces(resource, scope, chunk)))
        }
        for (chunk in logs.sortedBy { it.timeUnixNano }.chunked(MAX_ITEMS_PER_REQUEST)) {
            dropped += spool.put(Signal.LOGS, Gzip.compress(OtlpJsonEncoder.encodeLogs(resource, scope, chunk)))
        }
        if (usageGranted) {
            val metrics = state.metrics.export(nowMs)
            dropped += spool.put(Signal.METRICS, Gzip.compress(OtlpJsonEncoder.encodeMetrics(resource, scope, metrics)))
        }

        stateStore.save(state)
        if (usageGranted) queue.commitDrain()
        locationQueue.commitDrain()
        status.removePending(diagnostics)
        if (dropped.isNotEmpty()) {
            status.diagnostic(Severity.WARN, "Spool full: dropped ${dropped.size} oldest payload(s)")
        }
        status.recordRun(
            nowMs,
            if (usageGranted) {
                "${spans.size} span(s), ${logs.size} log(s), ${observations.size} browser observation(s), " +
                    "${locationLogs.size} location(s)"
            } else {
                "Usage Access not granted; ${locationLogs.size} location(s) collected"
            },
        )
    }

    private fun loadState(nowMs: Long): PersistedState {
        val loaded = try {
            stateStore.load()
        } catch (e: JSONException) {
            status.diagnostic(Severity.ERROR, "State file corrupt, starting over: ${e.message}")
            null
        }
        if (loaded != null) return loaded
        val keyguardSecure = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardSecure == true
        return PersistedState.initial(nowMs, keyguardSecure)
    }

    /** Emits `agent.start` on install/upgrade/boot and `agent.permission` on changes. */
    private fun agentLogs(state: PersistedState, nowMs: Long, logs: MutableList<LogData>) {
        val agent = state.agent
        val bootCount = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val reason = when {
            agent.lastVersion == null -> "install"
            agent.lastVersion != BuildConfig.VERSION_NAME -> "upgrade"
            bootCount != agent.lastBootCount -> "boot"
            else -> null
        }
        if (reason != null) {
            logs += agentLog(
                nowMs,
                "agent.start",
                "activitylog-android ${BuildConfig.VERSION_NAME} started ($reason)",
                Severity.INFO,
                listOf(attr("agent.start.reason", reason)),
            )
            agent.lastVersion = BuildConfig.VERSION_NAME
            agent.lastBootCount = bootCount
        }

        val usage = Permissions.hasUsageAccess(ctx)
        val accessibility = Permissions.isAccessibilityEnabled(ctx)
        if (reason != null || agent.usageAccessGranted != usage) {
            logs += permissionLog(nowMs, "usage_access", usage)
        }
        if (reason != null || agent.accessibilityEnabled != accessibility) {
            logs += permissionLog(nowMs, "accessibility", accessibility)
        }
        if (reason != null) {
            logs += permissionLog(nowMs, "background_location", Permissions.hasBackgroundLocation(ctx))
        }
        agent.usageAccessGranted = usage
        agent.accessibilityEnabled = accessibility
    }

    private fun permissionLog(nowMs: Long, name: String, granted: Boolean): LogData = agentLog(
        nowMs,
        "agent.permission",
        "Permission $name is ${if (granted) "granted" else "not granted"}",
        if (granted) Severity.INFO else Severity.WARN,
        listOf(attr("agent.permission.name", name), attr("agent.permission.granted", granted)),
    )

    private fun agentLog(
        nowMs: Long,
        eventName: String,
        body: String,
        severity: Severity,
        extra: List<net.ymotongpoo.activitylog.otlp.Attribute>,
    ) = LogData(
        timeUnixNano = nowMs.millisToNanos(),
        observedTimeUnixNano = nowMs.millisToNanos(),
        severity = severity,
        body = body,
        attributes = listOf(attr("event.name", eventName)) + extra,
    )

    private fun flushLocked(settings: AppSettings): FlushResult {
        val nowMs = System.currentTimeMillis()
        if (!settings.isExportConfigured) {
            val r = FlushResult(0, 0, spool.count(), retryable = false, lastError = "not configured")
            val missing = settings.missingExportSettings().joinToString(", ")
            val hint = if (settingsStore.tokenUnreadable()) {
                "; the saved token cannot be decrypted, enter it again"
            } else {
                ""
            }
            status.recordSend(nowMs, "Not sent: $missing not set$hint (${r.remaining} queued)")
            return r
        }
        val sender = OtlpSender(settings.endpoint, settings.instanceId, settings.token)
        val result = Flusher(
            spool = spool,
            send = { signal, body -> sender.send(signal, body) },
            onRejected = { signal, rejected: SendResult.Rejected ->
                status.diagnostic(
                    Severity.ERROR,
                    "OTLP ${signal.id} rejected with HTTP ${rejected.status}: ${rejected.body.take(500)}",
                )
            },
        ).flush()
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(nowMs))
        val summary = buildString {
            append(if (result.retryable) "Retry pending" else if (result.dropped > 0) "Partially rejected" else "OK")
            append(" at ").append(time)
            append(": sent ").append(result.sent)
            if (result.dropped > 0) append(", dropped ").append(result.dropped)
            append(", queued ").append(result.remaining)
            result.lastError?.let { append(" — ").append(it.take(300)) }
        }
        status.recordSend(nowMs, summary)
        if (result.retryable) Log.w(TAG, "send deferred: ${result.lastError}")
        return result
    }

    companion object {
        private const val TAG = "activitylog"
        private const val MAX_ITEMS_PER_REQUEST = 1000
        private const val LOCATION_TIMEOUT_MS = 20_000L
        private val LOCK = Any()
    }
}
