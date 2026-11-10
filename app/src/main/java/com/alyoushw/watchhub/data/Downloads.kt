package com.alyoushw.watchhub.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A film or episode saved on the device. */
@Serializable
data class Download(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val file: String,
    val systemId: Long,
    val entryJson: String,
)

/** Where a download is at: progress 0..1, done, or failed. */
data class DownloadState(val progress: Float, val done: Boolean, val failed: Boolean)

/**
 * Films and episodes from the Xtream account saved on the device, to watch without internet
 * (in the car, on a trip) in the same player. Android's download manager does the downloading
 * (with its own notification); the files stay in the app's folder and go with the app.
 */
class Downloads(private val context: Context) {
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val serializer = ListSerializer(Download.serializer())

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Download>> = _items.asStateFlow()

    private fun load(): List<Download> =
        prefs.getString("list", null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun store(list: List<Download>) {
        prefs.edit().putString("list", json.encodeToString(serializer, list)).apply()
        _items.value = list
    }

    fun has(key: String) = _items.value.any { it.key == key }

    /** Starts saving [entry] from [url]. */
    fun start(entry: WatchEntry, url: String) {
        if (has(entry.key)) return
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val ext = url.substringAfterLast('.', "mp4").substringBefore('?').take(5).ifBlank { "mp4" }
        val file = File(dir, entry.key.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + ext)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(listOfNotNull(entry.title, entry.subtitle).joinToString(" · "))
            .setDescription("Watch Hub · للمشاهدة بدون نت")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(true)
        val id = dm.enqueue(request)
        store(_items.value + Download(entry.key, entry.title, entry.subtitle, entry.image, file.absolutePath, id, json.encodeToString(WatchEntry.serializer(), entry)))
    }

    fun remove(key: String) {
        val d = _items.value.firstOrNull { it.key == key } ?: return
        runCatching { dm.remove(d.systemId) }
        runCatching { File(d.file).delete() }
        store(_items.value - d)
    }

    fun state(d: Download): DownloadState {
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
            )
        }
    }

    /** The saved file for [key], once fully downloaded, as a link the player can open. */
    fun localUrl(key: String): String? {
        val d = _items.value.firstOrNull { it.key == key } ?: return null
        val f = File(d.file)
        return if (f.exists() && state(d).done) Uri.fromFile(f).toString() else null
    }

    fun entryOf(d: Download): WatchEntry? = runCatching { json.decodeFromString(WatchEntry.serializer(), d.entryJson) }.getOrNull()
}
