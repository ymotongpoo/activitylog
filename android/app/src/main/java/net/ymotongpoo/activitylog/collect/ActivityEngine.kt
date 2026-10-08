package net.ymotongpoo.activitylog.collect

import net.ymotongpoo.activitylog.browser.BrowserObservation
import net.ymotongpoo.activitylog.browser.Browsers
import net.ymotongpoo.activitylog.browser.UrlParser
import net.ymotongpoo.activitylog.metrics.MetricNames
import net.ymotongpoo.activitylog.metrics.MetricsState
import net.ymotongpoo.activitylog.otlp.Attribute
import net.ymotongpoo.activitylog.otlp.LogData
import net.ymotongpoo.activitylog.otlp.Severity
import net.ymotongpoo.activitylog.otlp.SpanData
import net.ymotongpoo.activitylog.otlp.SpanLink
import net.ymotongpoo.activitylog.otlp.attr
import net.ymotongpoo.activitylog.otlp.millisToNanos

/** Spans and logs produced by one [ActivityEngine.process] call. */
class EngineOutput {
    val spans = mutableListOf<SpanData>()
    val logs = mutableListOf<LogData>()
}

/**
 * Turns usage events and browser observations into spans, logs and metric credits.
 * Pure Kotlin: Android APIs are reached only through [AppInfoResolver].
 *
 * - Root `active`: from KEYGUARD_HIDDEN (or SCREEN_INTERACTIVE on devices without a
 *   keyguard) until SCREEN_NON_INTERACTIVE / KEYGUARD_SHOWN / DEVICE_SHUTDOWN, split
 *   every [maxRootMs] into a new trace linked to the previous root.
 * - App span: from ACTIVITY_RESUMED of a package until the RESUMED of another package
 *   or the end of the root.
 * - `browser.tab`: each address bar observation inside a browser app span starts a
 *   segment that lasts until the next different observation or the app span end.
 *
 * Intervals still open at `now` stay in [TrackerState]; their elapsed time is credited
 * to the metrics up to `now` and remembered so that nothing is counted twice.
 */
class ActivityEngine(
    private val resolver: AppInfoResolver,
    private val privacy: PrivacyPolicy,
    private val ids: IdGenerator,
    private val maxRootMs: Long = DEFAULT_MAX_ROOT_MS,
    private val observationGraceMs: Long = DEFAULT_OBSERVATION_GRACE_MS,
) {
    fun process(
        state: TrackerState,
        metrics: MetricsState,
        events: List<UsageEvent>,
        observations: List<BrowserObservation>,
        nowMs: Long,
    ): EngineOutput = Run(state, metrics, nowMs).run(events, observations)

    private sealed interface Item {
        val timeMs: Long

        data class Event(override val timeMs: Long, val event: UsageEvent) : Item
        data class Observation(override val timeMs: Long, val obs: BrowserObservation) : Item
    }

    private inner class Run(
        private val st: TrackerState,
        private val metrics: MetricsState,
        private val nowMs: Long,
    ) {
        private val out = EngineOutput()
        private val appInfoCache = mutableMapOf<String, AppInfo>()

        fun run(events: List<UsageEvent>, observations: List<BrowserObservation>): EngineOutput {
            val begin = minOf(st.checkpointMs, nowMs)
            // Usage events keep their order; at equal timestamps they go before observations.
            val items = events.map { Item.Event(it.timeMs.coerceIn(begin, nowMs), it) } +
                observations.map { Item.Observation(it.timeMs.coerceIn(begin, nowMs), it) }
            for (item in items.sortedBy { it.timeMs }) {
                splitIfNeeded(item.timeMs)
                when (item) {
                    is Item.Event -> handleEvent(item.timeMs, item.event)
                    is Item.Observation -> handleObservation(item.timeMs, item.obs)
                }
            }
            splitIfNeeded(nowMs)
            creditOpenIntervals(nowMs)
            st.pending.entries.removeAll { nowMs - it.value.timeMs > observationGraceMs }
            st.checkpointMs = maxOf(st.checkpointMs, nowMs)
            return out
        }

        private fun handleEvent(t: Long, e: UsageEvent) {
            when (e.kind) {
                UsageEventKind.SCREEN_INTERACTIVE -> {
                    st.interactive = true
                    st.lastScreenOnMs = t
                    if (!effectivelyLocked() && st.root == null) startActive(t)
                    screenLog(t, "on", "Screen turned on")
                }
                UsageEventKind.SCREEN_NON_INTERACTIVE -> {
                    screenLog(t, "off", "Screen turned off")
                    st.interactive = false
                    endActive(t)
                }
                UsageEventKind.KEYGUARD_SHOWN -> {
                    screenLog(t, "locked", "Device locked")
                    st.locked = true
                    st.keyguardSeen = true
                    endActive(t)
                }
                UsageEventKind.KEYGUARD_HIDDEN -> {
                    st.locked = false
                    st.keyguardSeen = true
                    metrics.add(MetricNames.DEVICE_UNLOCKS, emptyList(), 1.0, t)
                    if (st.root == null) startActive(t)
                    screenLog(t, "unlocked", "Device unlocked")
                }
                UsageEventKind.DEVICE_SHUTDOWN -> {
                    endActive(t)
                    st.interactive = false
                    st.foregroundPackage = null
                }
                UsageEventKind.DEVICE_STARTUP -> {
                    endActive(t)
                    st.interactive = null
                    st.locked = null
                    st.foregroundPackage = null
                }
                UsageEventKind.ACTIVITY_RESUMED -> {
                    val pkg = e.packageName ?: return
                    st.foregroundPackage = pkg
                    st.foregroundResumedMs = t
                    if (st.root == null) {
                        // Covers the first run (state unknown) and missed screen events.
                        if (st.interactive != false && !effectivelyLocked()) startActive(t)
                    } else {
                        switchApp(pkg, t)
                    }
                }
            }
        }

        private fun handleObservation(t: Long, obs: BrowserObservation) {
            val app = st.app
            if (st.root != null && app != null && app.packageName == obs.packageName) {
                applyObservation(obs.text, t)
            } else {
                // The accessibility event may arrive slightly before ACTIVITY_RESUMED.
                st.pending[obs.packageName] = PendingObservation(t, obs.text)
            }
        }

        private fun effectivelyLocked(): Boolean = st.locked ?: st.keyguardSeen

        private fun startActive(t: Long, link: OpenRoot? = null) {
            st.root = OpenRoot(
                traceId = ids.traceId(),
                spanId = ids.spanId(),
                startMs = t,
                creditedUntilMs = t,
                linkTraceId = link?.traceId,
                linkSpanId = link?.spanId,
            )
            // Open the foreground app if it was resumed during this screen-on session
            // (it can be reported just before the keyguard goes away).
            val pkg = st.foregroundPackage
            if (link == null && pkg != null && st.foregroundResumedMs >= st.lastScreenOnMs) {
                openApp(pkg, t)
            }
        }

        private fun endActive(t: Long) {
            val root = st.root ?: return
            closeApp(t)
            creditRoot(root, t)
            if (t > root.startMs) {
                out.spans += SpanData(
                    traceId = root.traceId,
                    spanId = root.spanId,
                    parentSpanId = null,
                    name = "active",
                    startTimeUnixNano = root.startMs.millisToNanos(),
                    endTimeUnixNano = t.millisToNanos(),
                    attributes = listOf(
                        attr("activity.state", "active"),
                        attr("activity.app.switches", root.switches),
                    ),
                    links = if (root.linkTraceId != null && root.linkSpanId != null) {
                        listOf(SpanLink(root.linkTraceId, root.linkSpanId))
                    } else {
                        emptyList()
                    },
                )
            }
            st.root = null
        }

        private fun splitIfNeeded(t: Long) {
            while (true) {
                val root = st.root ?: return
                if (t - root.startMs <= maxRootMs) return
                val at = root.startMs + maxRootMs
                val pkg = st.app?.packageName
                val url = st.tab?.url
                endActive(at)
                startActive(at, link = root)
                if (pkg != null) {
                    openApp(pkg, at, usePending = false)
                    if (url != null) openTab(url, at)
                }
            }
        }

        private fun switchApp(pkg: String, t: Long) {
            val root = st.root ?: return
            val current = st.app
            if (current?.packageName == pkg) return
            if (current != null) {
                closeApp(t)
                root.switches++
                appSwitchLog(t, current.packageName, pkg)
            }
            openApp(pkg, t)
        }

        private fun openApp(pkg: String, t: Long, usePending: Boolean = true) {
            st.app = OpenApp(ids.spanId(), pkg, t, t)
            if (!usePending) return
            val pending = st.pending.remove(pkg) ?: return
            if (Browsers.isBrowser(pkg) && pending.timeMs <= t && t - pending.timeMs <= observationGraceMs) {
                applyObservation(pending.text, t)
            }
        }

        private fun closeApp(t: Long) {
            val app = st.app ?: return
            val root = st.root ?: return
            closeTab(t)
            val info = info(app.packageName)
            val excluded = privacy.isAppExcluded(app.packageName, info.label)
            if (!excluded) {
                creditApp(app, info, t)
                if (t > app.startMs) {
                    out.spans += SpanData(
                        traceId = root.traceId,
                        spanId = app.spanId,
                        parentSpanId = root.spanId,
                        name = info.label,
                        startTimeUnixNano = app.startMs.millisToNanos(),
                        endTimeUnixNano = t.millisToNanos(),
                        attributes = appAttributes(app.packageName, info),
                    )
                }
            }
            st.app = null
        }

        private fun applyObservation(text: String, t: Long) {
            val app = st.app ?: return
            if (!Browsers.isBrowser(app.packageName)) return
            val url = UrlParser.parse(text) ?: return
            val full = url.full
            if (st.tab?.url == full) return
            closeTab(t)
            openTab(full, maxOf(t, app.startMs))
        }

        private fun openTab(url: String, t: Long) {
            st.tab = OpenTab(ids.spanId(), url, t, t)
        }

        private fun closeTab(t: Long) {
            val tab = st.tab ?: return
            val app = st.app
            val root = st.root
            st.tab = null
            if (app == null || root == null) return
            val info = info(app.packageName)
            if (privacy.isAppExcluded(app.packageName, info.label)) return
            val url = UrlParser.parse(tab.url) ?: return
            if (privacy.isDomainExcluded(url.domain)) return
            creditTab(tab, url.domain, t)
            if (t <= tab.startMs) return
            val attrs = mutableListOf(
                attr("activity.context.kind", "browser.tab"),
                attr("activity.context.source", "accessibility"),
            )
            Browsers.browserName(app.packageName)?.let { attrs += attr("activity.browser.name", it) }
            attrs += privacy.urlAttributes(url)
            out.spans += SpanData(
                traceId = root.traceId,
                spanId = tab.spanId,
                parentSpanId = app.spanId,
                name = "browser.tab",
                startTimeUnixNano = tab.startMs.millisToNanos(),
                endTimeUnixNano = t.millisToNanos(),
                attributes = attrs,
            )
        }

        private fun creditOpenIntervals(t: Long) {
            val root = st.root ?: return
            creditRoot(root, t)
            val app = st.app ?: return
            val info = info(app.packageName)
            if (privacy.isAppExcluded(app.packageName, info.label)) {
                app.creditedUntilMs = maxOf(app.creditedUntilMs, t)
                st.tab?.let { it.creditedUntilMs = maxOf(it.creditedUntilMs, t) }
                return
            }
            creditApp(app, info, t)
            val tab = st.tab ?: return
            val url = UrlParser.parse(tab.url)
            if (url == null || privacy.isDomainExcluded(url.domain)) {
                tab.creditedUntilMs = maxOf(tab.creditedUntilMs, t)
            } else {
                creditTab(tab, url.domain, t)
            }
        }

        private fun creditRoot(root: OpenRoot, until: Long) {
            val delta = until - root.creditedUntilMs
            if (delta > 0) {
                metrics.add(MetricNames.ACTIVITY_TIME, MetricsState.ACTIVE_ATTRS, delta / 1000.0, until)
                root.creditedUntilMs = until
            }
        }

        private fun creditApp(app: OpenApp, info: AppInfo, until: Long) {
            val delta = until - app.creditedUntilMs
            if (delta > 0) {
                val seconds = delta / 1000.0
                metrics.add(
                    MetricNames.APP_TIME,
                    listOf("activity.app.name" to info.label, "activity.app.id" to app.packageName),
                    seconds,
                    until,
                )
                metrics.add(MetricNames.CATEGORY_TIME, listOf("activity.category" to info.category), seconds, until)
                app.creditedUntilMs = until
            }
        }

        private fun creditTab(tab: OpenTab, domain: String, until: Long) {
            val delta = until - tab.creditedUntilMs
            if (delta > 0) {
                metrics.add(MetricNames.BROWSER_DOMAIN_TIME, listOf("url.domain" to domain), delta / 1000.0, until)
                tab.creditedUntilMs = until
            }
        }

        private fun info(pkg: String): AppInfo = appInfoCache.getOrPut(pkg) { resolver.resolve(pkg) }

        private fun appAttributes(pkg: String, info: AppInfo): List<Attribute> = listOf(
            attr("activity.app.name", info.label),
            attr("activity.app.id", pkg),
            attr("activity.category", info.category),
        )

        private fun screenLog(t: Long, screenState: String, body: String) {
            val root = st.root
            out.logs += LogData(
                timeUnixNano = t.millisToNanos(),
                observedTimeUnixNano = nowMs.millisToNanos(),
                severity = Severity.INFO,
                body = body,
                attributes = listOf(
                    attr("event.name", "activity.device.screen"),
                    attr("activity.device.screen.state", screenState),
                ),
                traceId = root?.traceId,
                spanId = root?.spanId,
            )
        }

        private fun appSwitchLog(t: Long, fromPkg: String, toPkg: String) {
            val to = info(toPkg)
            if (privacy.isAppExcluded(toPkg, to.label)) return
            val from = info(fromPkg)
            val attrs = mutableListOf(attr("event.name", "activity.app.switch"))
            if (!privacy.isAppExcluded(fromPkg, from.label)) attrs += attr("activity.app.from", from.label)
            attrs += attr("activity.app.name", to.label)
            attrs += attr("activity.app.id", toPkg)
            val root = st.root
            out.logs += LogData(
                timeUnixNano = t.millisToNanos(),
                observedTimeUnixNano = nowMs.millisToNanos(),
                severity = Severity.INFO,
                body = "Switched to ${to.label}",
                attributes = attrs,
                traceId = root?.traceId,
                spanId = root?.spanId,
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_ROOT_MS = 60L * 60 * 1000
        const val DEFAULT_OBSERVATION_GRACE_MS = 3_000L
    }
}
