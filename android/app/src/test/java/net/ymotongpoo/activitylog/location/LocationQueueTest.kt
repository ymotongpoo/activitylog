package net.ymotongpoo.activitylog.location

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocationQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun recoversPendingAndInterruptedBatchesAfterRestart() {
        val a = LocationFix(1000, 35.0, 139.0, altitudeM = 123.0)
        val b = a.copy(timeMs = 2000)
        assertTrue(LocationQueue(tmp.root).append(a))
        assertEquals(listOf(a), LocationQueue(tmp.root).beginDrain())
        assertTrue(LocationQueue(tmp.root).append(b))
        val restarted = LocationQueue(tmp.root)
        assertEquals(listOf(a, b), restarted.beginDrain())
        restarted.commitDrain()
        assertTrue(LocationQueue(tmp.root).beginDrain().isEmpty())
    }

    @Test fun truncatedAppendDoesNotHideNextFix() {
        java.io.File(tmp.root, "locations.jsonl").writeText("{unfinished")
        val fix = LocationFix(1000, 35.0, 139.0)
        assertTrue(LocationQueue(tmp.root).append(fix))
        assertEquals(listOf(fix), LocationQueue(tmp.root).beginDrain())
    }

    @Test fun sizeLimitDoesNotDeleteUncommittedBatch() {
        val a = LocationFix(1000, 35.0, 139.0)
        val q = LocationQueue(tmp.root, maxBytes = 10)
        assertTrue(q.append(a))
        assertEquals(listOf(a), q.beginDrain())
        assertFalse(q.append(a.copy(timeMs = 2000)))
        assertEquals(listOf(a), LocationQueue(tmp.root, maxBytes = 10).beginDrain())
    }
}
