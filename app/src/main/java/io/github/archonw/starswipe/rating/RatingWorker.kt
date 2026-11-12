package io.github.archonw.starswipe.rating

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.archonw.starswipe.StarSwipeApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Schreibt wartende Bewertungen. Zwei Varianten: lokal (ohne Netz-Bedingung) und Netz (mit). */
class RatingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val local = inputData.getBoolean(KEY_LOCAL, false)
        val ratings = (applicationContext as StarSwipeApp).container.ratings
        var result: FlushResult
        var rounds = 0
        do {
            result = ratings.flush()
            rounds++
        } while (result.written > 0 && ratings.pendingCount().first() > 0 && rounds < 20)
        return if (result.retryLater && !local) Result.retry() else Result.success()
    }

    companion object {
        private const val KEY_LOCAL = "local"

        fun enqueue(context: Context, local: Boolean) {
            val b = OneTimeWorkRequestBuilder<RatingWorker>()
                .setInputData(workDataOf(KEY_LOCAL to local))
            if (!local) b.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (local) "rating-flush-local" else "rating-flush-net",
                ExistingWorkPolicy.APPEND_OR_REPLACE, b.build(),
            )
        }

        /** Vom Nutzer angestoßen: sofort versuchen, ohne auf Netz-Bedingung oder Wartezeit (Backoff) zu warten. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("rating-flush-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RatingWorker>().setInputData(workDataOf(KEY_LOCAL to true)).build())
        }
    }
}
