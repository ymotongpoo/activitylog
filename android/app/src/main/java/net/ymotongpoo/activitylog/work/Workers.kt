package net.ymotongpoo.activitylog.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Periodic (and on-demand) collection followed by a send attempt. Needs no network. */
class CollectWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val result = Pipeline(applicationContext).collectAndSend()
        if (result.retryable) Scheduler.enqueueFlush(applicationContext)
        return Result.success()
    }
}

/** Retries sending the spool once the network is available. */
class FlushWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val result = Pipeline(applicationContext).flush()
        return if (result.retryable) Result.retry() else Result.success()
    }
}

/** Checks delayed retries when connected; per-payload deadlines survive restarts. */
class RetryRejectedWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        Pipeline(applicationContext).retryRejected()
        Result.success()
    } catch (_: java.io.IOException) {
        Result.retry()
    }
}
