package com.alyoushw.watchhub.tv

import android.app.UiModeManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest

/** Publishes only this app's opted-in WATCHING items; no streaming account or playback access. */
class WatchNextPublisher(private val context: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun start() {
        val mode = context.getSystemService(UiModeManager::class.java)
        if (mode?.currentModeType != Configuration.UI_MODE_TYPE_TELEVISION) return
        scope.launch {
            combine(container.settings.watchNext, container.store.data) { enabled, data ->
                if (enabled) data.watchlist.filter { it.canPublishWatchNext() }.sortedByDescending { it.updatedAt }.take(20) else emptyList()
            }.collectLatest { items ->
                try { sync(items) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("WatchHubNext", "TV provider unavailable", e)
                }
            }
        }
    }

    private fun sync(items: List<WatchItem>) {
        val resolver = context.contentResolver
        val table = TvContract.WatchNextPrograms.CONTENT_URI
        val own = mutableMapOf<String, Long>()
        resolver.query(table, arrayOf("_id", "internal_provider_id", "package_name"), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(2) == context.packageName) {
                val key = c.getString(1) ?: continue
                val previous = own.put(key, c.getLong(0))
                if (previous != null) resolver.delete(ContentUris.withAppendedId(table, previous), null, null)
            }
        }
        for (item in items) {
            val next = if (item.type != MediaType.MOVIE) item.nextEpisode() else null
            // Do not advertise an unaired or already-finished next episode.
            if (item.type != MediaType.MOVIE && (next == null ||
                (item.latestAired != null && next > item.latestAired))) continue
            val values = ContentValues().apply {
                put("internal_provider_id", item.key)
                put("title", item.title)
                put("type", if (item.type == MediaType.MOVIE) 0 else 3)
                put("watch_next_type", if (item.type == MediaType.MOVIE) 0 else 1)
                put("last_engagement_time_utc_millis", item.updatedAt)
                put("searchable", 1)
                put("intent_uri", Uri.Builder().scheme("watchhub").authority("detail")
                    .appendPath(item.type.path).appendPath(item.id.toString()).build().toString())
                if (next != null) {
                    put("season_display_number", next.season.toString())
                    put("episode_display_number", next.episode.toString())
                    put("episode_title", "الموسم ${next.season} · الحلقة ${next.episode}")
                }
                val poster = item.posterPath
                if (!poster.isNullOrBlank()) put("poster_art_uri",
                    if (poster.startsWith("https://")) poster else "https://image.tmdb.org/t/p/w342$poster")
            }
            val row = own.remove(item.key)
            if (row == null) resolver.insert(table, values)
            else resolver.update(ContentUris.withAppendedId(table, row), values, null, null)
        }
        own.values.forEach { resolver.delete(ContentUris.withAppendedId(table, it), null, null) }
    }
}
