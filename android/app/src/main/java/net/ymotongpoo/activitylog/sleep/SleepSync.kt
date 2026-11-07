package net.ymotongpoo.activitylog.sleep

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ymotongpoo.activitylog.work.Pipeline
import net.ymotongpoo.activitylog.work.Scheduler
import java.time.Duration
import java.time.Instant

object SleepSync {
    val readPermission: String = HealthPermission.getReadPermission(SleepSessionRecord::class)
    const val backgroundPermission = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    private val mutex = Mutex()

    fun available(context: Context): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    fun backgroundAvailable(client: HealthConnectClient): Boolean = client.features.getFeatureStatus(
        HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
    ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    suspend fun sync(context: Context, background: Boolean): Int = mutex.withLock {
        val store = SleepStore(context)
        if (!store.enabled) return@withLock 0
        if (!available(context)) {
            store.status("Health Connect is unavailable. Install or update it, then open Sleep records.")
            return@withLock 0
        }
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (readPermission !in granted) {
            store.status("Sleep access is not granted. Open Sleep records to allow it.")
            return@withLock 0
        }
        if (background && (!backgroundAvailable(client) || backgroundPermission !in granted)) {
            store.status("Background sleep access is unavailable or not granted. Use Import now in Sleep records.")
            return@withLock 0
        }
        val until = Instant.now()
        val from = until.minus(Duration.ofDays(28))
        val records = mutableListOf<SleepSession>()
        var token: String? = null
        val seenTokens = mutableSetOf<String>()
        do {
            val response = client.readRecords(ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, until),
                dataOriginFilter = setOf(DataOrigin(SleepSession.GARMIN_PACKAGE)),
                pageSize = 500, pageToken = token,
            ))
            for (record in response.records) {
                require(record.metadata.dataOrigin.packageName == SleepSession.GARMIN_PACKAGE)
                // Health Connect sessions should be complete before being written.
                // A future-ended/in-progress record must not be turned into an observed sleep state.
                if (record.endTime > until) continue
                records += SleepSession(
                    SleepSession.opaqueId(record.metadata.dataOrigin.packageName, record.metadata.id),
                    record.metadata.dataOrigin.packageName, record.startTime.toEpochMilli(), record.endTime.toEpochMilli(),
                    record.metadata.lastModifiedTime.toEpochMilli(), record.startZoneOffset?.totalSeconds,
                    record.endZoneOffset?.totalSeconds, record.stages.map {
                        SleepInterval(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), SleepStage.fromCode(it.stage))
                    },
                ).normalized()
            }
            require(records.size <= 10_000) { "Too many sleep sessions" }
            token = response.pageToken?.takeIf { it.isNotEmpty() }
            require(token == null || seenTokens.add(token)) { "Health Connect repeated a page token" }
        } while (token != null)
        // A failed/revoked/incomplete read never infers deletions or advances the checkpoint.
        val changes = Pipeline(context).importSleep(records, from.toEpochMilli(), until.toEpochMilli())
        if (changes > 0) Scheduler.enqueueFlush(context)
        changes
    }
}
