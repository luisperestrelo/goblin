package com.luisperestrelo.goblin.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.luisperestrelo.goblin.data.repo.SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * One-shot worker that seeds the local database with the deepest available
 * transaction history right after a (re-)authorization. It runs as normal
 * WorkManager work rather than a foreground/viewModel coroutine so it survives
 * the app being backgrounded or killed mid-backfill: a 3-year pull across two
 * accounts is dozens of paginated calls and takes a while.
 *
 * It is deliberately not expedited - the user has just interacted with the app
 * so the device is awake and the work starts within seconds anyway, which keeps
 * it comfortably inside the bank's ~1h post-SCA deep-history window without the
 * foreground-notification obligation expedited work carries below API 31.
 */
@HiltWorker
class BackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        syncRepository.syncNow(forceFullHistory = true)
        Result.success()
    } catch (e: Exception) {
        // Transient bank errors (429/5xx) are common; back off and retry while
        // the deep-history window is still open, then give up.
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "goblin-post-auth-backfill"
        private const val MAX_ATTEMPTS = 5

        /** Enqueues the backfill, replacing any in-flight one from a prior auth. */
        fun enqueue(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<BackfillWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
