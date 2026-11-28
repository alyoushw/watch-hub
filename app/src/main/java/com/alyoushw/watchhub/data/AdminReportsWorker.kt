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
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * The owner's account only: every so often checks the account for problem reports nobody handled and shows a
 * notification (also when the app is closed). Tapping it opens the admin page on its reports.
 */
class AdminReportsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? WatchHubApp)?.container ?: return Result.success()
        val cloud = container.cloud
        // The account is restored a moment after the process starts.
        repeat(20) {
            if (cloud.state.value is CloudSync.State.SignedIn || cloud.state.value is CloudSync.State.SignedOut) return@repeat
            delay(1_000)
        }
        if (!cloud.isAdmin) return Result.success()
        val reports = runCatching { cloud.adminReports() }.getOrNull() ?: return Result.retry()
        val open = reports.filter { !it.done }
        val prefs = applicationContext.getSharedPreferences("admin_reports", Context.MODE_PRIVATE)
        val seen = prefs.getLong("notified_at", 0)
        val fresh = open.filter { it.at > seen }
        if (fresh.isEmpty()) return Result.success()
        prefs.edit().putLong("notified_at", fresh.maxOf { it.at }).apply()
        val latest = fresh.first()
        val who = latest.name?.takeIf { it.isNotBlank() } ?: latest.email ?: "مستخدم"
        val title = if (open.size > 1) "📨 ${open.size} بلاغات مفتوحة" else "📨 بلاغ جديد من $who"
        val body = (latest.place?.let { "$it\n" }.orEmpty() + latest.text).trim().ifBlank { latest.problem.orEmpty() }
        notify(applicationContext, title, body)
        return Result.success()
    }

    private fun notify(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("admin_reports", "بلاغات المستخدمين", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context, 77,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, "admin_reports")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(9_900, n) }
    }

    companion object {
        const val ACTION_OPEN = "com.alyoushw.watchhub.OPEN_ADMIN"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AdminReportsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("admin-reports", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
