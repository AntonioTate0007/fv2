package com.fortress.app.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fortress.app.data.preferences.AppPreferences
import com.fortress.app.data.repository.FortressRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic background check (15-min cadence) that fetches open positions and fires a local
 * lock-screen notification for any that newly crossed the 50% profit-take threshold.
 *
 * Replaces the FCM push that would normally come from the backend — works without any
 * Google project / push provider, runs entirely on-device + your existing API.
 */
class ProfitWatchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val repo = FortressRepository(ctx)

        val positions = runCatching { repo.positions() }.getOrElse {
            // Transient network failure — let WorkManager retry on its own backoff.
            return Result.retry()
        }

        FortressFirebaseMessagingService.ensureChannel(ctx)

        val alreadyAlerted = AppPreferences.alertedPositionIdsFlow(ctx).first()
        positions
            .filter { it.atProfitTarget && it.id !in alreadyAlerted }
            .forEach { pos ->
                ProfitAlertManager.notify(ctx, pos)
                AppPreferences.markAlerted(ctx, pos.id)
            }

        // Drop ids for positions that no longer exist (closed) so we re-alert if a fresh
        // position with the same id ever shows up.
        AppPreferences.pruneAlerted(ctx, positions.map { it.id }.toSet())

        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "fortress.profit_watch"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ProfitWatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
