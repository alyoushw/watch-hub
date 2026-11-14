package com.alyoushw.watchhub.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alyoushw.watchhub.MainActivity
import com.alyoushw.watchhub.R
import com.alyoushw.watchhub.WatchHubApp
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * "نزلت الحلقة الجاية": for the series the user is following (watched lately in their account,
 * or "أتابعه" in their list) that are still airing, a notification the day a new episode comes
 * out, and whether it is already in their Xtream account.
 */
class NewEpisodesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? WatchHubApp)?.container ?: return Result.success()
        if (container.settings.apiKey.value.isBlank()) return Result.success()
        val prefs = applicationContext.getSharedPreferences("new_episodes", Context.MODE_PRIVATE)
        val series = mutableMapOf<Int, String>()
        // Series watched in the last 45 days.
        val since = System.currentTimeMillis() - 45L * 86_400_000
        container.history.entries.value.filter { it.kind == WatchEntry.EPISODE && it.updatedAt > since }
            .distinctBy { it.seriesId }.take(15).forEach { e ->
                container.recommender.tmdbIdFor(MediaType.TV, e.title)?.let { series[it] = XtreamApi.normalizeTitle(e.title).ifBlank { e.title } }
            }
        container.store.data.value.watchlist.filter { it.type == MediaType.TV && it.status == WatchStatus.WATCHING }.forEach { series[it.id] = it.title }

        val today = LocalDate.now()
        for ((id, fallbackName) in series) {
            val d = runCatching { container.api.details(MediaType.TV, id) }.getOrNull() ?: continue
            val last = d.lastEpisodeToAir ?: continue
            val aired = runCatching { LocalDate.parse(last.airDate) }.getOrNull() ?: continue
            if (aired.isBefore(today.minusDays(2)) || aired.isAfter(today)) continue
            val mark = "S${last.seasonNumber}E${last.episodeNumber}"
            if (prefs.getString("$id", null) == mark) continue
            prefs.edit().putString("$id", mark).apply()
            val name = d.displayTitle.ifBlank { fallbackName }
            // Already in one of the user's accounts?
            val inAccount = container.settings.xtreamAccounts.value.filter { it.isComplete }.firstOrNull { acc ->
                runCatching { container.xtream.findMatch(XtreamKind.SERIES, id, listOf(name, fallbackName), d.year, acc) }.getOrNull() != null
            }
            notify(applicationContext, id, "نزلت الحلقة ${last.episodeNumber} من «$name»", if (inAccount != null) "الموسم ${last.seasonNumber} · موجود في حسابك «${inAccount.label}»" else "الموسم ${last.seasonNumber}")
        }
        return Result.success()
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("episodes", "حلقات جديدة", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, "episodes")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(10_000 + id % 100_000, n) }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewEpisodesWorker>(8, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("new-episodes", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
