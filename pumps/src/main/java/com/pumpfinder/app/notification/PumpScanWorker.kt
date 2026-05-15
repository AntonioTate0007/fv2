package com.pumpfinder.app.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pumpfinder.app.data.PumpRepository
import java.util.concurrent.TimeUnit

/**
 * Polls the scanner every ~15 minutes (the WorkManager floor) and fires a
 * LOCAL notification for any candidate at-or-above the user's score threshold
 * that the worker hasn't already alerted on. Works whether or not the backend
 * pushes an FCM message — important because Render's free plan sleeps after
 * 15 min idle, and the FCM push from a sleeping server arrives never.
 */
class PumpScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val repo = PumpRepository(applicationContext)
        val resp = repo.scan().getOrElse { return Result.retry() }

        val minScore = repo.minAlertScore()
        val alreadySeen = repo.seenTickers()
        val fresh = resp.candidates.filter { it.score >= minScore && it.ticker !in alreadySeen }
        if (fresh.isEmpty()) return Result.success()

        NotificationChannels.ensure(applicationContext)
        fresh.forEach { c ->
            PumpFcmService.post(
                ctx = applicationContext,
                ticker = c.ticker,
                score = c.score,
                price = "%.2f".format(c.price),
                changePct = "%.2f".format(c.changePct),
                relVol = "%.1f".format(c.relVol),
                yahooUrl = c.yahooUrl,
            )
        }
        repo.markSeen(fresh.map { it.ticker })
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "pumps.scan_worker"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<PumpScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
