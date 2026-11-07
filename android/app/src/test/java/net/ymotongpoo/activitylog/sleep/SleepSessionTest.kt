package net.ymotongpoo.activitylog.sleep

import net.ymotongpoo.activitylog.otlp.OtlpJsonEncoder
import net.ymotongpoo.activitylog.otlp.Scope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SleepSessionTest {
    private val start = 1_790_000_000_000L
    private fun session(id: String = "test-session", startMs: Long = start) = SleepSession(
        id, SleepSession.GARMIN_PACKAGE, startMs, startMs + 3_600_000, startMs + 3_600_001,
        9 * 3600, 9 * 3600,
        listOf(
            SleepInterval(startMs + 600_000, startMs + 1_200_000, SleepStage.LIGHT),
            SleepInterval(startMs + 1_200_000, startMs + 1_800_000, SleepStage.LIGHT),
            SleepInterval(startMs + 2_400_000, startMs + 3_600_000, SleepStage.REM),
        ),
    )

    @Test fun gapsStayUnknownAndAdjacentIdenticalStagesMergeWithoutResampling() {
        val normalized = session().normalized()
        assertEquals(listOf(SleepStage.UNKNOWN, SleepStage.LIGHT, SleepStage.UNKNOWN, SleepStage.REM), normalized.stages.map { it.stage })
        assertEquals(3_600_000L, normalized.stages.sumOf { it.endMs - it.startMs })
        assertEquals(start, normalized.stages.first().startMs)
        assertEquals(start + 1_800_000, normalized.stages[1].endMs)
        assertEquals(normalized, normalized.normalized())
        assertEquals(SleepStage.UNKNOWN, SleepStage.fromCode(999))
    }

    @Test fun absentStagesDoNotInventAwakeOrAsleepData() {
        val empty = session().copy(stages = emptyList()).normalized()
        assertEquals(listOf(SleepInterval(start, start + 3_600_000, SleepStage.UNKNOWN)), empty.stages)
        val json = JSONObject(SleepLogs.build(listOf(SleepReconciler.Change(empty, false)), start + 8_000_000).single().body)
        assertEquals(3600.0, json.getDouble("unknown_seconds"), 0.0)
        assertEquals(0.0, json.getDouble("sleeping_seconds"), 0.0)
        assertEquals(0.0, json.getDouble("awake_seconds"), 0.0)
    }

    @Test fun invalidOverlapsAndOutOfSessionIntervalsAreRejected() {
        for (intervals in listOf(
            listOf(SleepInterval(start - 1, start + 100, SleepStage.LIGHT)),
            listOf(SleepInterval(start, start + 3_600_001, SleepStage.REM)),
            listOf(SleepInterval(start, start, SleepStage.DEEP)),
            listOf(SleepInterval(start, start + 200, SleepStage.LIGHT), SleepInterval(start + 100, start + 300, SleepStage.DEEP)),
        )) assertThrows(IllegalArgumentException::class.java) { session().copy(stages = intervals).normalized() }
    }

    @Test fun resyncAndMetadataOnlyUpdatesDoNotEmitDuplicateSnapshots() {
        val first = SleepReconciler.reconcile(emptyMap(), listOf(session()), start - 100, start + 5_000_000)
        assertEquals(1, first.changes.size)
        val repeat = SleepReconciler.reconcile(first.sessions, listOf(session().copy(modifiedMs = start + 4_000_000)), start - 100, start + 5_000_000)
        assertTrue(repeat.changes.isEmpty())
        assertEquals(start + 4_000_000, repeat.sessions.getValue("test-session").modifiedMs)
    }

    @Test fun correctedStageReplacesOneWholeSnapshotWithANewRevision() {
        val original = session().normalized()
        val corrected = original.copy(stages = original.stages.map { if (it.stage == SleepStage.LIGHT) it.copy(stage = SleepStage.DEEP) else it })
        val update = SleepReconciler.reconcile(mapOf(original.id to original), listOf(corrected), start - 100, start + 5_000_000)
        assertEquals(1, update.changes.size)
        assertFalse(update.changes.single().deleted)
        val before = JSONObject(SleepLogs.build(listOf(SleepReconciler.Change(original, false)), start + 5_000_000).single().body)
        val after = JSONObject(SleepLogs.build(update.changes, start + 5_000_001).single().body)
        assertNotEquals(before.getString("revision"), after.getString("revision"))
        assertEquals(0.0, after.getDouble("light_seconds"), 0.0)
        assertEquals(1200.0, after.getDouble("deep_seconds"), 0.0)
        assertEquals(original.id, after.getString("session_id"))
    }

    @Test fun onlyMissingRecordsInsideTheCompletedReadWindowAreDeleted() {
        val active = session().normalized()
        val old = session("older", start - 20_000_000).normalized()
        val boundary = session("boundary", start - 600_000).normalized()
        val previous = listOf(active, old, boundary).associateBy { it.id }
        val update = SleepReconciler.reconcile(previous, emptyList(), start - 100, start + 5_000_000)
        assertEquals(listOf(active), update.changes.map { it.session })
        assertTrue(update.changes.single().deleted)
        assertFalse(update.sessions.containsKey(old.id)) // Aging out is not a remote deletion.
        assertTrue(update.sessions.containsKey(boundary.id))
        val deletion = JSONObject(SleepLogs.build(update.changes, start + 5_000_000).single().body)
        assertEquals("delete", deletion.getString("operation"))
        assertEquals(0, deletion.getInt("session_count"))
        assertEquals(0, deletion.getJSONArray("stages").length())
        for (stage in SleepStage.entries) assertEquals(0.0, deletion.getDouble("${stage.key}_seconds"), 0.0)
    }

    @Test fun codecKeepsOffsetsAndAllIntervalsAcrossRestarts() {
        val overnight = session().copy(startOffsetSeconds = -5 * 3600, endOffsetSeconds = -4 * 3600).normalized()
        val rows = mapOf(overnight.id to overnight)
        assertEquals(rows, SleepCodec.decodeState(SleepCodec.encodeState(rows)))
    }

    @Test fun oldSleepTimesRemainInBodyWhileImportTimestampIsUsedForOtlp() {
        val normalized = session().normalized()
        val observed = start + 20L * 24 * 3600 * 1000
        val changes = listOf(SleepReconciler.Change(normalized, false))
        val logs = SleepLogs.build(changes, observed)
        val replay = SleepLogs.build(changes, observed + 1000)
        assertEquals(logs.single().attributes, replay.single().attributes) // Stable replay identity.
        val body = JSONObject(logs.single().body)
        assertEquals(start, body.getLong("start_unix_ms"))
        assertEquals(normalized.endMs, body.getLong("end_unix_ms"))
        assertEquals(3600.0, SleepStage.entries.sumOf { body.getDouble("${it.key}_seconds") }, 0.0)
        val encoded = JSONObject(OtlpJsonEncoder.encodeLogs(emptyList(), Scope("test", "1"), logs))
            .getJSONArray("resourceLogs").getJSONObject(0).getJSONArray("scopeLogs").getJSONObject(0)
            .getJSONArray("logRecords").getJSONObject(0)
        assertEquals((observed * 1_000_000).toString(), encoded.getString("timeUnixNano"))
        assertEquals(start, JSONObject(encoded.getJSONObject("body").getString("stringValue")).getLong("start_unix_ms"))
    }

    @Test fun sourceRecordIdsAreOpaqueStableAndSourceScoped() {
        val id = SleepSession.opaqueId(SleepSession.GARMIN_PACKAGE, "private-health-connect-id")
        assertEquals(32, id.length)
        assertEquals(id, SleepSession.opaqueId(SleepSession.GARMIN_PACKAGE, "private-health-connect-id"))
        assertNotEquals(id, SleepSession.opaqueId("other.app", "private-health-connect-id"))
        assertFalse(id.contains("private"))
    }

    @Test fun duplicateIdsAndForeignDataCannotCreateADeletionCheckpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            SleepReconciler.reconcile(emptyMap(), listOf(session(), session()), start - 100, start + 5_000_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SleepReconciler.reconcile(emptyMap(), listOf(session().copy(source = "other.app")), start - 100, start + 5_000_000)
        }
    }
}
