package net.ymotongpoo.activitylog.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObservationQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun twoPhaseDrain() {
        val q = ObservationQueue(tmp.root)
        val a = BrowserObservation(1, "com.android.chrome", "example.com/\"quoted\"")
        val b = BrowserObservation(2, "com.brave.browser", "example.org")
        q.append(a)
        assertEquals(listOf(a), q.beginDrain())
        // Not committed (e.g. the job died): the next drain sees it again plus new data.
        q.append(b)
        assertEquals(listOf(a, b), q.beginDrain())
        q.commitDrain()
        assertTrue(q.beginDrain().isEmpty())
    }

    @Test
    fun ignoresCorruptLines() {
        assertNull(BrowserObservation.fromJsonLine("{not json"))
        assertNull(BrowserObservation.fromJsonLine(""))
        val o = BrowserObservation(5, "p", "u")
        assertEquals(o, BrowserObservation.fromJsonLine(o.toJsonLine()))
    }

    @Test
    fun capsQueueSize() {
        val q = ObservationQueue(tmp.root, maxBytes = 10)
        assertTrue(q.append(BrowserObservation(1, "com.android.chrome", "example.com")))
        assertEquals(false, q.append(BrowserObservation(2, "com.android.chrome", "example.org")))
    }
}
