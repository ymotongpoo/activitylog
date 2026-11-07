package net.ymotongpoo.activitylog.sleep

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import net.ymotongpoo.activitylog.BuildConfig
import java.util.concurrent.TimeUnit

class SleepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        SleepSync.sync(applicationContext, background = true)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        SleepStore(applicationContext).status("Sleep access changed. Open Sleep records to review permissions.")
        Result.success()
    } catch (e: Exception) {
        SleepStore(applicationContext).status("Sleep import failed (${e.javaClass.simpleName}); will retry.")
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

object SleepScheduler {
    private const val WORK = "health-connect-sleep"
    fun ensure(context: Context) {
        if (!BuildConfig.GARMIN_EXPORT_ENABLED) return
        val manager = WorkManager.getInstance(context)
        if (!SleepStore(context).enabled) {
            manager.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequest.Builder(SleepWorker::class.java, 15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
