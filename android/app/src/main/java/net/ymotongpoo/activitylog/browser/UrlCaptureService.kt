package net.ymotongpoo.activitylog.browser

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the address bar of supported browsers and appends the text to the
 * [ObservationQueue]. The service is restricted to browser packages by its XML config.
 */
class UrlCaptureService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val lastRecorded = mutableMapOf<String, Recorded>()
    private var pendingPackage: String? = null
    private var windowChanged = false
    private val readRunnable = Runnable { readAddressBar() }
    private lateinit var queue: ObservationQueue

    private data class Recorded(val text: String, val timeMs: Long)

    override fun onServiceConnected() {
        super.onServiceConnected()
        queue = ObservationQueue(applicationContext.filesDir)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (!Browsers.isBrowser(pkg)) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) windowChanged = true
        // Content-changed events arrive in bursts; read once things settle.
        pendingPackage = pkg
        handler.removeCallbacks(readRunnable)
        handler.postDelayed(readRunnable, DEBOUNCE_MS)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(readRunnable)
        super.onDestroy()
    }

    private fun readAddressBar() {
        val pkg = pendingPackage ?: return
        val root = try {
            rootInActiveWindow
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot read active window", e)
            null
        } ?: return
        if (root.packageName?.toString() != pkg) return
        val text = findAddressText(root, pkg) ?: return
        val now = System.currentTimeMillis()
        val last = lastRecorded[pkg]
        // Identical consecutive values are skipped, except when the window changed
        // (e.g. returning to the browser) or the last record is getting old.
        val duplicate = last != null && last.text == text && !windowChanged && now - last.timeMs < REFRESH_MS
        windowChanged = false
        if (duplicate) return
        if (::queue.isInitialized && queue.append(BrowserObservation(now, pkg, text))) {
            lastRecorded[pkg] = Recorded(text, now)
        }
    }

    private fun findAddressText(root: AccessibilityNodeInfo, pkg: String): String? {
        for (id in ADDRESS_BAR_IDS) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
            } catch (_: RuntimeException) {
                continue
            }
            for (node in nodes) {
                // While the user edits the omnibox the text is a query, not the page URL.
                if (node.isFocused) return null
                val text = (node.text ?: node.contentDescription)?.toString()?.trim()
                if (!text.isNullOrEmpty() && UrlParser.parse(text) != null) return text
            }
        }
        return null
    }

    private companion object {
        const val TAG = "UrlCaptureService"
        const val DEBOUNCE_MS = 700L
        const val REFRESH_MS = 5L * 60 * 1000
        val ADDRESS_BAR_IDS = listOf("url_bar", "location_bar_status")
    }
}
