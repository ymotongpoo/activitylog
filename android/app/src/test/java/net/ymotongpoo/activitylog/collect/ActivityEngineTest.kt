package net.ymotongpoo.activitylog.collect

import net.ymotongpoo.activitylog.browser.BrowserObservation
import net.ymotongpoo.activitylog.metrics.MetricNames
import net.ymotongpoo.activitylog.metrics.MetricsState
import net.ymotongpoo.activitylog.otlp.AttributeValue
import net.ymotongpoo.activitylog.otlp.LogData
import net.ymotongpoo.activitylog.otlp.SpanData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityEngineTest {
    private val t0 = 1_760_000_000_000L
    private fun s(seconds: Long) = t0 + seconds * 1000
    private fun m(minutes: Long) = s(minutes * 60)

    private class SeqIds : IdGenerator {
        private var n = 0L
        override fun traceId(): String = (++n).toString(16).padStart(32, '0')
        override fun spanId(): String = (++n).toString(16).padStart(16, '0')
    }

    private val labels = mapOf(
        "com.example.a" to "App A",
        "com.example.b" to "App B",
        "com.android.chrome" to "Chrome",
        "com.secret.vault" to "Vault",
    )
    private val resolver = AppInfoResolver { pkg -> AppInfo(labels[pkg] ?: pkg, "Android/productivity") }
    private val ids = SeqIds()

    private fun engine(privacy: PrivacyPolicy = PrivacyPolicy()) = ActivityEngine(resolver, privacy, ids)

    private fun lockedDevice() = TrackerState(checkpointMs = t0, interactive = false, locked = true, keyguardSeen = true)

    private fun ev(t: Long, kind: UsageEventKind, pkg: String? = null) = UsageEvent(t, kind, pkg)
    private fun obs(t: Long, pkg: String, text: String) = BrowserObservation(t, pkg, text)

    private fun SpanData.str(key: String): String? =
        (attributes.firstOrNull { it.key == key }?.value as? AttributeValue.Str)?.value

    private fun SpanData.int(key: String): Long? =
        (attributes.firstOrNull { it.key == key }?.value as? AttributeValue.IntVal)?.value

    private fun LogData.str(key: String): String? =
        (attributes.firstOrNull { it.key == key }?.value as? AttributeValue.Str)?.value

    private fun ms(nanos: Long) = nanos / 1_000_000

    private fun appTime(metrics: MetricsState, pkg: String) =
        metrics.value(MetricNames.APP_TIME, listOf("activity.app.name" to (labels[pkg] ?: pkg), "activity.app.id" to pkg))

    private fun activeTime(metrics: MetricsState) = metrics.value(MetricNames.ACTIVITY_TIME, MetricsState.ACTIVE_ATTRS)

    /** Simulates process death between runs by round-tripping through the codec. */
    private fun reload(state: TrackerState, metrics: MetricsState): PersistedState =
        StateCodec.decode(StateCodec.encode(PersistedState(state, metrics)))

    @Test
    fun unlockAppSwitchAndScreenOff() {
        val state = lockedDevice()
        val metrics = MetricsState(t0)
        val out = engine().process(
            state,
            metrics,
            listOf(
                ev(s(1), UsageEventKind.SCREEN_INTERACTIVE),
                ev(s(3), UsageEventKind.KEYGUARD_HIDDEN),
                ev(s(4), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
                ev(s(64), UsageEventKind.ACTIVITY_RESUMED, "com.example.b"),
                ev(s(70), UsageEventKind.ACTIVITY_RESUMED, "com.example.b"),
                ev(s(124), UsageEventKind.SCREEN_NON_INTERACTIVE),
                ev(s(125), UsageEventKind.KEYGUARD_SHOWN),
            ),
            emptyList(),
            s(200),
        )

        val root = out.spans.single { it.name == "active" }
        assertNull(root.parentSpanId)
        assertEquals(s(3), ms(root.startTimeUnixNano))
        assertEquals(s(124), ms(root.endTimeUnixNano))
        assertEquals("active", root.str("activity.state"))
        assertEquals(1L, root.int("activity.app.switches"))

        val a = out.spans.single { it.name == "App A" }
        val b = out.spans.single { it.name == "App B" }
        for (app in listOf(a, b)) {
            assertEquals(root.traceId, app.traceId)
            assertEquals(root.spanId, app.parentSpanId)
            assertEquals("Android/productivity", app.str("activity.category"))
        }
        assertEquals("com.example.a", a.str("activity.app.id"))
        assertEquals("App A", a.str("activity.app.name"))
        assertEquals(s(4), ms(a.startTimeUnixNano))
        assertEquals(s(64), ms(a.endTimeUnixNano))
        assertEquals(s(64), ms(b.startTimeUnixNano))
        assertEquals(s(124), ms(b.endTimeUnixNano))
        assertEquals(3, out.spans.size)

        assertEquals(121.0, activeTime(metrics), 1e-9)
        assertEquals(60.0, appTime(metrics, "com.example.a"), 1e-9)
        assertEquals(60.0, appTime(metrics, "com.example.b"), 1e-9)
        assertEquals(120.0, metrics.value(MetricNames.CATEGORY_TIME, listOf("activity.category" to "Android/productivity")), 1e-9)
        assertEquals(1.0, metrics.value(MetricNames.DEVICE_UNLOCKS), 1e-9)

        val screen = out.logs.filter { it.str("event.name") == "activity.device.screen" }
        assertEquals(listOf("on", "unlocked", "off", "locked"), screen.map { it.str("activity.device.screen.state") })
        assertNull(screen[0].traceId)
        assertEquals(root.traceId, screen[1].traceId)
        assertEquals(root.spanId, screen[1].spanId)
        assertEquals(root.traceId, screen[2].traceId)
        val switch = out.logs.single { it.str("event.name") == "activity.app.switch" }
        assertEquals("App A", switch.str("activity.app.from"))
        assertEquals("com.example.b", switch.str("activity.app.id"))

        assertNull(state.root)
        assertNull(state.app)
        assertEquals(s(200), state.checkpointMs)
    }

    @Test
    fun screenOnWithoutUnlockIsNotActive() {
        val state = lockedDevice()
        val metrics = MetricsState(t0)
        val out = engine().process(
            state,
            metrics,
            listOf(
                ev(s(1), UsageEventKind.SCREEN_INTERACTIVE),
                // Apps shown over the lock screen (camera, calls) do not start activity.
                ev(s(2), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
                ev(s(30), UsageEventKind.SCREEN_NON_INTERACTIVE),
            ),
            emptyList(),
            s(60),
        )
        assertTrue(out.spans.isEmpty())
        assertEquals(0.0, activeTime(metrics), 0.0)
    }

    @Test
    fun deviceWithoutKeyguardStartsActiveOnScreenOn() {
        val state = TrackerState(checkpointMs = t0, interactive = false)
        val metrics = MetricsState(t0)
        val out = engine().process(
            state,
            metrics,
            listOf(
                ev(s(1), UsageEventKind.SCREEN_INTERACTIVE),
                ev(s(2), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
                ev(s(10), UsageEventKind.SCREEN_NON_INTERACTIVE),
                ev(s(20), UsageEventKind.SCREEN_INTERACTIVE),
                ev(s(21), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
                ev(s(30), UsageEventKind.SCREEN_NON_INTERACTIVE),
            ),
            emptyList(),
            s(40),
        )
        val roots = out.spans.filter { it.name == "active" }
        assertEquals(listOf(s(1) to s(10), s(20) to s(30)), roots.map { ms(it.startTimeUnixNano) to ms(it.endTimeUnixNano) })
        val apps = out.spans.filter { it.name == "App A" }
        assertEquals(listOf(s(2) to s(10), s(21) to s(30)), apps.map { ms(it.startTimeUnixNano) to ms(it.endTimeUnixNano) })
        assertEquals(19.0, activeTime(metrics), 1e-9)
        assertEquals(0.0, metrics.value(MetricNames.DEVICE_UNLOCKS), 0.0)
    }

    @Test
    fun appResumedJustBeforeKeyguardHiddenIsOpenedAtUnlock() {
        val state = lockedDevice()
        val metrics = MetricsState(t0)
        val out = engine().process(
            state,
            metrics,
            listOf(
                ev(s(1), UsageEventKind.SCREEN_INTERACTIVE),
                ev(s(2), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
                ev(s(3), UsageEventKind.KEYGUARD_HIDDEN),
                ev(s(13), UsageEventKind.SCREEN_NON_INTERACTIVE),
            ),
            emptyList(),
            s(20),
        )
        val a = out.spans.single { it.name == "App A" }
        assertEquals(s(3), ms(a.startTimeUnixNano))
        assertEquals(s(13), ms(a.endTimeUnixNano))
    }

    @Test
    fun openIntervalsAreCarriedOverBetweenRuns() {
        var state = lockedDevice()
        var metrics = MetricsState(t0)
        val run1 = engine().process(
            state,
            metrics,
            listOf(
                ev(s(0), UsageEventKind.KEYGUARD_HIDDEN),
                ev(s(1), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
            ),
            emptyList(),
            m(10),
        )
        assertTrue("open intervals must not be emitted early", run1.spans.isEmpty())
        val openRoot = state.root!!
        val openApp = state.app!!
        assertEquals(600.0, activeTime(metrics), 1e-9)
        assertEquals(599.0, appTime(metrics, "com.example.a"), 1e-9)

        reload(state, metrics).let { state = it.tracker; metrics = it.metrics }
        assertEquals(openRoot, state.root)
        assertEquals(openApp, state.app)

        // A run with no events only credits the elapsed time.
        engine().process(state, metrics, emptyList(), emptyList(), m(15))
        assertEquals(900.0, activeTime(metrics), 1e-9)

        reload(state, metrics).let { state = it.tracker; metrics = it.metrics }
        val run3 = engine().process(
            state,
            metrics,
            listOf(ev(m(20), UsageEventKind.SCREEN_NON_INTERACTIVE)),
            emptyList(),
            m(21),
        )
        val root = run3.spans.single { it.name == "active" }
        assertEquals(openRoot.traceId, root.traceId)
        assertEquals(openRoot.spanId, root.spanId)
        assertEquals(s(0), ms(root.startTimeUnixNano))
        assertEquals(m(20), ms(root.endTimeUnixNano))
        val app = run3.spans.single { it.name == "App A" }
        assertEquals(openApp.spanId, app.spanId)
        assertEquals(s(1), ms(app.startTimeUnixNano))

        // Nothing is counted twice: totals equal the interval lengths.
        assertEquals(1200.0, activeTime(metrics), 1e-9)
        assertEquals(1199.0, appTime(metrics, "com.example.a"), 1e-9)
        assertNull(state.root)
    }

    @Test
    fun rootIsSplitEveryHourWithLinks() {
        val state = lockedDevice()
        val metrics = MetricsState(t0)
        val out = engine().process(
            state,
            metrics,
            listOf(
                ev(s(0), UsageEventKind.KEYGUARD_HIDDEN),
                ev(s(0), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
                ev(m(150), UsageEventKind.SCREEN_NON_INTERACTIVE),
            ),
            emptyList(),
            m(160),
        )
        val roots = out.spans.filter { it.name == "active" }.sortedBy { it.startTimeUnixNano }
        assertEquals(
            listOf(m(0) to m(60), m(60) to m(120), m(120) to m(150)),
            roots.map { ms(it.startTimeUnixNano) to ms(it.endTimeUnixNano) },
        )
        assertEquals(3, roots.map { it.traceId }.toSet().size)
        assertTrue(roots[0].links.isEmpty())
        assertEquals(roots[0].traceId, roots[1].links.single().traceId)
        assertEquals(roots[0].spanId, roots[1].links.single().spanId)
        assertEquals(roots[1].spanId, roots[2].links.single().spanId)

        val apps = out.spans.filter { it.name == "App A" }.sortedBy { it.startTimeUnixNano }
        assertEquals(3, apps.size)
        for ((app, root) in apps.zip(roots)) {
            assertEquals(root.traceId, app.traceId)
            assertEquals(root.spanId, app.parentSpanId)
            assertEquals(root.startTimeUnixNano, app.startTimeUnixNano)
            assertEquals(root.endTimeUnixNano, app.endTimeUnixNano)
        }
        assertEquals(9000.0, activeTime(metrics), 1e-9)
        assertEquals(9000.0, appTime(metrics, "com.example.a"), 1e-9)
    }

    @Test
    fun splitAcrossRunsEmitsOnlyClosedRoots() {
        var state = lockedDevice()
        var metrics = MetricsState(t0)
        val run1 = engine().process(
            state,
            metrics,
            listOf(
                ev(s(0), UsageEventKind.KEYGUARD_HIDDEN),
                ev(s(0), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
            ),
            emptyList(),
            m(90),
        )
        val first = run1.spans.single { it.name == "active" }
        assertEquals(m(60), ms(first.endTimeUnixNano))
        val second = state.root!!
        assertEquals(m(60), second.startMs)
        assertEquals(first.spanId, second.linkSpanId)

        reload(state, metrics).let { state = it.tracker; metrics = it.metrics }
        val run2 = engine().process(state, metrics, listOf(ev(m(100), UsageEventKind.KEYGUARD_SHOWN)), emptyList(), m(105))
        val root2 = run2.spans.single { it.name == "active" }
        assertEquals(second.traceId, root2.traceId)
        assertEquals(first.spanId, root2.links.single().spanId)
        assertEquals(6000.0, activeTime(metrics), 1e-9)
    }

    @Test
    fun browserObservationsBecomeTabSpans() {
        val state = lockedDevice()
        val metrics = MetricsState(t0)
        val chrome = "com.android.chrome"
        val out = engine().process(
            state,
            metrics,
            listOf(
                ev(s(0), UsageEventKind.KEYGUARD_HIDDEN),
                ev(s(10), UsageEventKind.ACTIVITY_RESUMED, chrome),
                ev(s(100), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
                ev(s(200), UsageEventKind.SCREEN_NON_INTERACTIVE),
            ),
            listOf(
                // Reported slightly before ACTIVITY_RESUMED: clamped to the app span start.
                obs(s(9), chrome, "github.com/ymotongpoo/activitylog"),
                obs(s(30), chrome, "github.com/ymotongpoo/activitylog"),
                obs(s(40), chrome, "https://Example.org/x?q=secret#top"),
                obs(s(50), chrome, "not a url"),
                // Chrome is not in the foreground any more.
                obs(s(150), chrome, "github.com/other"),
            ),
            s(300),
        )
        val app = out.spans.single { it.name == "Chrome" }
        val tabs = out.spans.filter { it.name == "browser.tab" }.sortedBy { it.startTimeUnixNano }
        assertEquals(2, tabs.size)
        for (tab in tabs) {
            assertEquals(app.spanId, tab.parentSpanId)
            assertEquals(app.traceId, tab.traceId)
            assertEquals("browser.tab", tab.str("activity.context.kind"))
            assertEquals("accessibility", tab.str("activity.context.source"))
            assertEquals("chrome", tab.str("activity.browser.name"))
        }
        assertEquals(s(10) to s(40), ms(tabs[0].startTimeUnixNano) to ms(tabs[0].endTimeUnixNano))
        assertEquals("github.com", tabs[0].str("url.domain"))
        assertEquals("https://github.com/ymotongpoo/activitylog", tabs[0].str("url.full"))
        assertEquals(s(40) to s(100), ms(tabs[1].startTimeUnixNano) to ms(tabs[1].endTimeUnixNano))
        assertEquals("example.org", tabs[1].str("url.domain"))
        assertEquals("https", tabs[1].str("url.scheme"))
        assertEquals("/x", tabs[1].str("url.path"))
        // Default URL mode is "path": query and fragment are dropped.
        assertEquals("https://example.org/x", tabs[1].str("url.full"))

        assertEquals(30.0, metrics.value(MetricNames.BROWSER_DOMAIN_TIME, listOf("url.domain" to "github.com")), 1e-9)
        assertEquals(60.0, metrics.value(MetricNames.BROWSER_DOMAIN_TIME, listOf("url.domain" to "example.org")), 1e-9)
    }

    @Test
    fun browserTabIsCarriedOverAndSplitWithRoot() {
        var state = lockedDevice()
        var metrics = MetricsState(t0)
        val chrome = "com.android.chrome"
        val run1 = engine(PrivacyPolicy(urlMode = UrlMode.FULL)).process(
            state,
            metrics,
            listOf(
                ev(m(0), UsageEventKind.KEYGUARD_HIDDEN),
                ev(m(1), UsageEventKind.ACTIVITY_RESUMED, chrome),
            ),
            listOf(obs(m(2), chrome, "news.example.com/a?id=1")),
            m(10),
        )
        assertTrue(run1.spans.isEmpty())
        val tab = state.tab!!
        assertEquals("https://news.example.com/a?id=1", tab.url)
        assertEquals(480.0, metrics.value(MetricNames.BROWSER_DOMAIN_TIME, listOf("url.domain" to "news.example.com")), 1e-9)

        reload(state, metrics).let { state = it.tracker; metrics = it.metrics }
        val run2 = engine(PrivacyPolicy(urlMode = UrlMode.FULL)).process(
            state,
            metrics,
            listOf(ev(m(70), UsageEventKind.SCREEN_NON_INTERACTIVE)),
            emptyList(),
            m(80),
        )
        val tabs = run2.spans.filter { it.name == "browser.tab" }.sortedBy { it.startTimeUnixNano }
        assertEquals(listOf(m(2) to m(60), m(60) to m(70)), tabs.map { ms(it.startTimeUnixNano) to ms(it.endTimeUnixNano) })
        assertEquals(tab.spanId, tabs[0].spanId)
        assertEquals("https://news.example.com/a?id=1", tabs[1].str("url.full"))
        val roots = run2.spans.filter { it.name == "active" }.sortedBy { it.startTimeUnixNano }
        assertEquals(roots[1].traceId, tabs[1].traceId)
        assertNotEquals(tabs[0].traceId, tabs[1].traceId)
        assertEquals(68.0 * 60, metrics.value(MetricNames.BROWSER_DOMAIN_TIME, listOf("url.domain" to "news.example.com")), 1e-9)
    }

    @Test
    fun excludedPackageHasNoSpanButCountsAsActive() {
        val state = lockedDevice()
        val metrics = MetricsState(t0)
        val out = engine(PrivacyPolicy(excludedPackages = listOf("com.secret.*"))).process(
            state,
            metrics,
            listOf(
                ev(s(0), UsageEventKind.KEYGUARD_HIDDEN),
                ev(s(0), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
                ev(s(10), UsageEventKind.ACTIVITY_RESUMED, "com.secret.vault"),
                ev(s(40), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
                ev(s(50), UsageEventKind.SCREEN_NON_INTERACTIVE),
            ),
            emptyList(),
            s(60),
        )
        assertTrue(out.spans.none { it.str("activity.app.id") == "com.secret.vault" })
        assertEquals(2, out.spans.count { it.name == "App A" })
        assertEquals(2L, out.spans.single { it.name == "active" }.int("activity.app.switches"))
        assertEquals(50.0, activeTime(metrics), 1e-9)
        assertEquals(0.0, appTime(metrics, "com.secret.vault"), 0.0)
        assertEquals(20.0, appTime(metrics, "com.example.a"), 1e-9)
        assertTrue(out.logs.none { it.str("activity.app.id") == "com.secret.vault" })
        assertTrue(out.logs.none { it.str("activity.app.from") == "Vault" })
    }

    @Test
    fun excludedDomainHasNoTabSpanOrMetric() {
        val state = lockedDevice()
        val metrics = MetricsState(t0)
        val chrome = "com.android.chrome"
        val out = engine(PrivacyPolicy(excludedDomains = listOf("*.bank.example"))).process(
            state,
            metrics,
            listOf(
                ev(s(0), UsageEventKind.KEYGUARD_HIDDEN),
                ev(s(0), UsageEventKind.ACTIVITY_RESUMED, chrome),
                ev(s(100), UsageEventKind.SCREEN_NON_INTERACTIVE),
            ),
            listOf(
                obs(s(10), chrome, "www.bank.example/login"),
                obs(s(50), chrome, "example.com"),
            ),
            s(120),
        )
        val tabs = out.spans.filter { it.name == "browser.tab" }
        assertEquals(listOf("example.com"), tabs.map { it.str("url.domain") })
        assertTrue(metrics.allSeries().none { it.attributes.contains("url.domain" to "www.bank.example") })
        assertEquals(100.0, appTime(metrics, chrome), 1e-9)
    }

    @Test
    fun firstRunWithUnknownStateStartsOnResume() {
        val state = TrackerState(checkpointMs = t0)
        val metrics = MetricsState(t0)
        engine().process(
            state,
            metrics,
            listOf(ev(s(5), UsageEventKind.ACTIVITY_RESUMED, "com.example.a")),
            emptyList(),
            s(65),
        )
        assertNotNull(state.root)
        assertEquals(s(5), state.root!!.startMs)
        assertEquals(60.0, appTime(metrics, "com.example.a"), 1e-9)
    }

    @Test
    fun shutdownClosesActive() {
        val state = lockedDevice()
        val metrics = MetricsState(t0)
        val out = engine().process(
            state,
            metrics,
            listOf(
                ev(s(0), UsageEventKind.KEYGUARD_HIDDEN),
                ev(s(1), UsageEventKind.ACTIVITY_RESUMED, "com.example.a"),
                ev(s(30), UsageEventKind.DEVICE_SHUTDOWN),
                ev(s(90), UsageEventKind.DEVICE_STARTUP),
                // Launcher behind the keyguard after boot does not start activity.
                ev(s(95), UsageEventKind.ACTIVITY_RESUMED, "com.example.b"),
            ),
            emptyList(),
            s(120),
        )
        assertEquals(s(30), ms(out.spans.single { it.name == "active" }.endTimeUnixNano))
        assertNull(state.root)
        assertEquals(30.0, activeTime(metrics), 1e-9)
    }
}
