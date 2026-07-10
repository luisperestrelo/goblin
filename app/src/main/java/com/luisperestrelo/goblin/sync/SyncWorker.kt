package com.luisperestrelo.goblin.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.luisperestrelo.goblin.data.repo.SyncRepository
import com.luisperestrelo.goblin.widget.WidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic incremental sync. Runs every 6h, which is exactly the PSD2 cap of 4
 * unattended AIS fetches per day. Refreshes the widget on success.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val widgetUpdater: WidgetUpdater,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        syncRepository.syncNow()
        widgetUpdater.update()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "goblin-periodic-sync"
        private const val MAX_ATTEMPTS = 3
        private const val SYNC_INTERVAL_HOURS = 6L

        /** Schedules the periodic sync, keeping any existing schedule intact. */
        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
