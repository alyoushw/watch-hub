package com.alyoushw.watchhub.data

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alyoushw.watchhub.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** A film or episode saved (or being saved) on the device. */
@Serializable
data class Download(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val file: String,
    /** Old downloads went through Android's download manager; new ones have [url] (-1 here). */
    val systemId: Long = -1,
    val entryJson: String,
    val url: String = "",
)

/** Where a download is at, with its speed (bytes per second) while it runs. */
data class DownloadState(
    val progress: Float,
    val done: Boolean,
    val failed: Boolean,
    val bytes: Long = 0,
    val total: Long = 0,
    val paused: Boolean = false,
    val speed: Long = 0,
    val waiting: Boolean = false,
)

/**
 * Films and episodes from the Xtream account saved on the device, to watch without internet
 * (car, trips) in the same player. The app downloads them itself (in the background, with a
 * notification), so they can be paused and picked up later from where they stopped.
 */
class Downloads(private val context: Context) {
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val serializer = ListSerializer(Download.serializer())

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Download>> = _items.asStateFlow()

    /** Live progress of the downloads running now (speed included), by key. */
    private val _live = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val live: StateFlow<Map<String, DownloadState>> = _live.asStateFlow()

    private fun load(): List<Download> =
        prefs.getString("list", null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun store(list: List<Download>) {
        prefs.edit().putString("list", json.encodeToString(serializer, list)).apply()
        _items.value = list
    }

    fun has(key: String) = _items.value.any { it.key == key }

    fun find(key: String) = _items.value.firstOrNull { it.key == key }

    /** Starts saving [entry] from [url]. */
    fun start(entry: WatchEntry, url: String) {
        if (has(entry.key)) return
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val ext = url.substringAfterLast('.', "mp4").substringBefore('?').take(5).ifBlank { "mp4" }
        val file = File(dir, entry.key.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + ext)
        file.delete()
        store(_items.value + Download(entry.key, entry.title, entry.subtitle, entry.image, file.absolutePath, -1, json.encodeToString(WatchEntry.serializer(), entry), url))
        setStatus(entry.key, STATUS_QUEUED)
        enqueue(entry.key)
    }

    private fun enqueue(key: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putString("key", key).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("dl:$key", ExistingWorkPolicy.REPLACE, request)
    }

    /** Stops for now; the part already saved stays, and [resume] goes on from there. */
    fun pause(key: String) {
        setStatus(key, STATUS_PAUSED)
        WorkManager.getInstance(context).cancelUniqueWork("dl:$key")
        _live.value = _live.value - key
    }

    fun resume(key: String) {
        if (find(key)?.url.isNullOrBlank()) return
        setStatus(key, STATUS_QUEUED)
        enqueue(key)
    }

    fun remove(key: String) {
        val d = find(key) ?: return
        WorkManager.getInstance(context).cancelUniqueWork("dl:$key")
        if (d.systemId >= 0) runCatching { dm.remove(d.systemId) }
        runCatching { File(d.file).delete() }
        prefs.edit().remove("st:$key").remove("tot:$key").apply()
        _live.value = _live.value - key
        store(_items.value - d)
    }

    fun canPause(d: Download) = d.systemId < 0 && d.url.isNotBlank()

    internal fun status(key: String) = prefs.getString("st:$key", STATUS_QUEUED) ?: STATUS_QUEUED

    internal fun setStatus(key: String, status: String) = prefs.edit().putString("st:$key", status).apply()

    internal fun setTotal(key: String, total: Long) = prefs.edit().putLong("tot:$key", total).apply()

    internal fun publish(key: String, state: DownloadState?) {
        _live.value = if (state == null) _live.value - key else _live.value + (key to state)
    }

    fun state(d: Download): DownloadState {
        if (d.systemId >= 0) return legacyState(d)
        _live.value[d.key]?.let { return it }
        val f = File(d.file)
        val bytes = if (f.exists()) f.length() else 0L
        val total = prefs.getLong("tot:${d.key}", 0L)
        val progress = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        return when (status(d.key)) {
            STATUS_DONE -> DownloadState(1f, done = true, failed = false, bytes = bytes, total = total)
            STATUS_FAILED -> DownloadState(progress, done = false, failed = true, bytes = bytes, total = total)
            STATUS_PAUSED -> DownloadState(progress, done = false, failed = false, bytes = bytes, total = total, paused = true)
            else -> DownloadState(progress, done = false, failed = false, bytes = bytes, total = total, waiting = true)
        }
    }

    private fun legacyState(d: Download): DownloadState {
        val c = runCatching { dm.query(DownloadManager.Query().setFilterById(d.systemId)) }.getOrNull()
            ?: return DownloadState(1f, File(d.file).exists(), !File(d.file).exists())
        c.use {
            if (!it.moveToFirst()) return DownloadState(1f, File(d.file).exists(), !File(d.file).exists())
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return DownloadState(
                if (total > 0) (got.toFloat() / total).coerceIn(0f, 1f) else 0f,
                status == DownloadManager.STATUS_SUCCESSFUL,
                status == DownloadManager.STATUS_FAILED,
                got.coerceAtLeast(0),
                total.coerceAtLeast(0),
                waiting = status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_PENDING,
            )
        }
    }

    /** The saved file for [key], once fully downloaded, as a link the player can open. */
    fun localUrl(key: String): String? {
        val d = find(key) ?: return null
        val f = File(d.file)
        return if (f.exists() && state(d).done) Uri.fromFile(f).toString() else null
    }

    /**
     * Hands the link to ADM (Advanced Download Manager), which opens its own "new download"
     * window. False when ADM is not installed. Its files are ADM's: this app does not track them.
     */
    fun sendToAdm(activity: Context, url: String, name: String): Boolean {
        for (pkg in AdmPackages) {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, url)
                .putExtra(android.content.Intent.EXTRA_SUBJECT, name)
                .putExtra("title", name)
                .setPackage(pkg)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            for (i in listOf(send, view)) {
                if (runCatching { activity.startActivity(i) }.isSuccess) return true
            }
        }
        return false
    }

    fun entryOf(d: Download): WatchEntry? = runCatching { json.decodeFromString(WatchEntry.serializer(), d.entryJson) }.getOrNull()

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_RUNNING = "running"
        const val STATUS_PAUSED = "paused"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"

        /** ADM's free and paid editions. */
        private val AdmPackages = listOf("com.dv.adm", "com.dv.adm.pay", "com.dv.adm.old")
    }
}

/**
 * Downloads one file in the background (kept alive by a notification), continuing from the part
 * already saved (HTTP range). Pausing cancels this work; the file stays for the next run.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as com.alyoushw.watchhub.WatchHubApp).container

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloads = container.downloads
        val key = inputData.getString("key") ?: return@withContext Result.failure()
        val d = downloads.find(key) ?: return@withContext Result.failure()
        val file = File(d.file)
        downloads.setStatus(key, Downloads.STATUS_RUNNING)
        runCatching { setForeground(foreground(d, 0f)) }

        val client = Net.streams.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
        var have = if (file.exists()) file.length() else 0L
        val request = Request.Builder().url(d.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        try {
            client.newCall(request).execute().use { res ->
                if (res.code == 416) {
                    // Nothing left to fetch: it was already complete.
                    downloads.setStatus(key, Downloads.STATUS_DONE)
                    downloads.publish(key, null)
                    return@withContext Result.success()
                }
                if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
                val body = res.body ?: throw java.io.IOException("empty")
                // 206: the server continues from where we stopped; 200: it starts over.
                val append = res.code == 206 && have > 0
                if (!append) have = 0
                val total = if (body.contentLength() > 0) have + body.contentLength() else 0L
                if (total > 0) downloads.setTotal(key, total)

                var got = have
                var lastAt = System.currentTimeMillis()
                var lastBytes = got
                var speed = 0L
                var lastNote = 0L
                FileOutputStream(file, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val now = System.currentTimeMillis()
                            if (now - lastAt >= 1_000) {
                                // Smoothed, so the number does not jump about.
                                val instant = (got - lastBytes) * 1000 / (now - lastAt)
                                speed = if (speed == 0L) instant else (speed * 0.6 + instant * 0.4).toLong()
                                lastAt = now
                                lastBytes = got
                                val p = if (total > 0) (got.toFloat() / total).coerceIn(0f, 1f) else 0f
                                downloads.publish(key, DownloadState(p, done = false, failed = false, bytes = got, total = total, speed = speed))
                                if (now - lastNote > 3_000) {
                                    lastNote = now
                                    runCatching { setForeground(foreground(d, p)) }
                                }
                            }
                        }
                    }
                }
                downloads.setStatus(key, Downloads.STATUS_DONE)
                downloads.publish(key, null)
                Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Paused (or removed): keep what was saved.
            if (downloads.status(key) == Downloads.STATUS_RUNNING) downloads.setStatus(key, Downloads.STATUS_PAUSED)
            downloads.publish(key, null)
            throw e
        } catch (e: Exception) {
            downloads.publish(key, null)
            // A dropped connection: try again a few times (it continues from where it stopped).
            if (runAttemptCount < 4 && downloads.status(key) != Downloads.STATUS_PAUSED) {
                downloads.setStatus(key, Downloads.STATUS_QUEUED)
                Result.retry()
            } else {
                downloads.setStatus(key, Downloads.STATUS_FAILED)
                Result.failure()
            }
        }
    }

    private fun foreground(d: Download, progress: Float): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("downloads", "التنزيلات", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ننزّل «${d.title}»")
            .setContentText(listOfNotNull(d.subtitle, "${Math.round(progress * 100)}%").joinToString(" · "))
            .setProgress(100, Math.round(progress * 100), progress <= 0f)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val id = 20_000 + (d.key.hashCode() and 0xFFFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }
}
