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
 * Periodic background check (15-min cadence). Fetches the Autopilot activity feed
 * and fires a local notification for any newly-placed trades the user hasn't seen,
 * so trade execution surfaces even when the app is closed.
 */
class ProfitWatchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val repo = FortressRepository(ctx)

        val activity = runCatching { repo.activity() }.getOrElse { return Result.retry() }
        FortressFirebaseMessagingService.ensureChannel(ctx)

        val seen = AppPreferences.alertedPositionIdsFlow(ctx).first()
        activity
            .filter { it.type == "TRADE" && it.id !in seen }
            .forEach { item ->
                ProfitAlertManager.notify(ctx, item)
                AppPreferences.markAlerted(ctx, item.id)
            }

        // Keep the seen-set bounded to the ids that still exist in the feed.
        AppPreferences.pruneAlerted(ctx, activity.map { it.id }.toSet())
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "autopilot.trade_watch"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<ProfitWatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
