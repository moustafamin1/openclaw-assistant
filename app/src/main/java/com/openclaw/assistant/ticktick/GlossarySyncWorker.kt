package com.openclaw.assistant.ticktick

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.openclaw.assistant.data.SettingsRepository
import java.util.concurrent.TimeUnit

class GlossarySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository.getInstance(applicationContext)
        if (!settings.tickTickGlossaryWeeklyEnabled && !inputData.getBoolean(KEY_MANUAL_TRIGGER, false)) {
            return Result.success()
        }

        return when (GlossarySyncClient().syncAndSave(applicationContext)) {
            is GlossarySyncOutcome.Success -> Result.success()
            is GlossarySyncOutcome.Failure -> Result.retry()
        }
    }

    companion object {
        const val KEY_MANUAL_TRIGGER = "manual_trigger"
    }
}

object GlossarySyncScheduler {
    private const val PERIODIC_WORK_NAME = "ticktick_glossary_weekly_sync"
    private const val ONE_TIME_WORK_NAME = "ticktick_glossary_immediate_sync"
    private const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L

    private fun connectedConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<GlossarySyncWorker>(7, TimeUnit.DAYS)
            .setConstraints(connectedConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(ONE_TIME_WORK_NAME)
    }

    fun enqueueImmediateCatchUpIfStale(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val settings = SettingsRepository.getInstance(context)
        if (!settings.tickTickGlossaryWeeklyEnabled) return
        val lastSync = settings.tickTickGlossaryLastSyncAt
        if (lastSync == 0L || (nowMs - lastSync) > SEVEN_DAYS_MS) {
            enqueueOneTimeSync(context, manualTrigger = false)
        }
    }

    fun enqueueManualSync(context: Context) {
        enqueueOneTimeSync(context, manualTrigger = true)
    }

    private fun enqueueOneTimeSync(context: Context, manualTrigger: Boolean) {
        val data = androidx.work.workDataOf(GlossarySyncWorker.KEY_MANUAL_TRIGGER to manualTrigger)
        val request = OneTimeWorkRequestBuilder<GlossarySyncWorker>()
            .setConstraints(connectedConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
