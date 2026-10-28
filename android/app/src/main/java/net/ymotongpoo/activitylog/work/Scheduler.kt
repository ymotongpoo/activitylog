package net.ymotongpoo.activitylog.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val PERIODIC = "collect-periodic"
    private const val NOW = "collect-now"
    private const val FLUSH = "flush"

    /** Collection runs every 15 minutes regardless of connectivity; payloads are spooled. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequest.Builder(CollectWorker::class.java, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequest.Builder(CollectWorker::class.java).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Sends the spool as soon as a network is connected, with exponential backoff. */
    fun enqueueFlush(context: Context) {
        val request = OneTimeWorkRequest.Builder(FlushWorker::class.java)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // Keep a wake-up arriving while the current flush is finishing. With
        // KEEP, an inbox commit after its drain could wait until the next job.
        WorkManager.getInstance(context).enqueueUniqueWork(FLUSH, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
