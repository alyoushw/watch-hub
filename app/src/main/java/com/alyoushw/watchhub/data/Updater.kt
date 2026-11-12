package com.alyoushw.watchhub.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alyoushw.watchhub.BuildConfig
import com.alyoushw.watchhub.MainActivity
import com.alyoushw.watchhub.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps the app up to date from its GitHub releases ("build-N"): finds a newer build than this
 * one ([BuildConfig.BUILD_NUMBER]), downloads its APK and hands it to Android's installer (the
 * user confirms there; the same signing key lets it install over this one and keep everything).
 */
class Updater(private val context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private val _available = MutableStateFlow<AppUpdate?>(null)

    /** A newer build, once found (the update card shows while this is set). */
    val available: StateFlow<AppUpdate?> = _available.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)

    /** Download progress 0..1 while an update downloads, else null. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    val currentBuild: Int get() = BuildConfig.BUILD_NUMBER

    private var lastCheck = 0L

    /** Looks again when the app comes to the front, at most once every 10 minutes. */
    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - lastCheck < 10 * 60_000L) return
        check()
    }

    /**
     * Asks GitHub for the newest release; sets [available] when it is newer than this app.
     * [manual] (the Settings button) shows the card again even after "لاحقاً".
     */
    suspend fun check(manual: Boolean = false): AppUpdate? = withContext(Dispatchers.IO) {
        lastCheck = System.currentTimeMillis()
        if (manual) {
            prefs.edit().remove(KEY_DISMISSED).apply()
            _available.value = null
        }
        val found = runCatching {
            http.newCall(
                Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build(),
            ).execute().use { res ->
                if (!res.isSuccessful) return@use null
                parseAppUpdate(res.body?.string().orEmpty())
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        val newer = found?.takeIf { isNewerBuild(currentBuild, it.build) }
        // A temporary GitHub failure must not remove an update the user already saw.
        if (found != null) _available.value = newer
        newer
    }

    /** "لاحقاً": hides this build's card until a newer one comes. */
    fun dismiss(update: AppUpdate) {
        prefs.edit().putInt(KEY_DISMISSED, update.build).apply()
        _available.value = null
    }

    fun wasDismissed(update: AppUpdate) = prefs.getInt(KEY_DISMISSED, 0) >= update.build

    /** Android's "install unknown apps" switch for this app (needed once). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Downloads [update] (showing [progress]) and opens the installer. Throws with a message on failure. */
    suspend fun downloadAndInstall(update: AppUpdate) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "WatchHub-${update.build}.apk")
            _progress.value = 0f
            try {
                http.newCall(Request.Builder().url(update.url).build()).execute().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("ما قدرنا ننزّل التحديث (${res.code})")
                    val body = res.body ?: throw IllegalStateException("ما وصل الملف")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) _progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                throw IllegalStateException("انقطع التنزيل، تأكد من الإنترنت")
            } finally {
                _progress.value = null
            }
            out
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Checks every few hours in the background and posts a notification for a new build. */
    fun scheduleBackgroundChecks() {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** The phone / TV notification "يوجد تحديث جديد" (once per build). */
    fun notify(update: AppUpdate) {
        if (prefs.getInt(KEY_NOTIFIED, 0) >= update.build) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "تحديثات التطبيق", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("يوجد تحديث جديد لـ Watch Hub")
            .setContentText("النسخة ${update.build} جاهزة، افتح التطبيق واضغط «تحديث»")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (runCatching { nm.notify(1, n) }.isSuccess) {
            prefs.edit().putInt(KEY_NOTIFIED, update.build).apply()
        }
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/alyoushw/watch-hub/releases/latest"
        private const val KEY_DISMISSED = "dismissed"
        private const val KEY_NOTIFIED = "notified"
        private const val CHANNEL = "updates"
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updater = Updater(applicationContext)
        updater.check()?.let(updater::notify)
        return Result.success()
    }
}
