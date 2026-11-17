package com.alyoushw.watchhub.tv

import android.app.UiModeManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.MainActivity
import com.alyoushw.watchhub.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest

/** Only opted-in, current-profile content. TV home receives stable local links, never stream URLs. */
class WatchNextPublisher(private val context: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Card(val key: String, val values: ContentValues)
    private data class Publication(val next: List<Card>, val channel: List<Card>)

    fun start() {
        val mode = context.getSystemService(UiModeManager::class.java)
        if (mode?.currentModeType != Configuration.UI_MODE_TYPE_TELEVISION) return
        scope.launch {
            combine(container.settings.watchNext, container.store.data, container.history.entries,
                container.settings.xtreamAccounts) { enabled, data, entries, accounts ->
                if (!enabled) null else {
                    val seen = mutableSetOf<String>()
                    val recent = entries.sortedByDescending { it.updatedAt }.filter { e ->
                        !e.isEpisode || e.seriesId == null || seen.add("${e.accountId}:${e.seriesId}")
                    }.filter { e -> !e.isLive && !e.finished && e.positionMs >= 10_000 &&
                        (e.animeRef != null || e.sourceRef != null || e.fourURef != null || accounts.any { it.id == e.accountId && it.isComplete }) }
                    val identities = recent.mapNotNull(::identity).toSet()
                    val watching = data.watchlist.filter { it.canPublishWatchNext() && it.key !in identities }
                        .sortedByDescending { it.updatedAt }.take(20)
                    val resumed = recent.take(20).map(::resumeCard)
                    val library = data.watchlist.filter { it.status != WatchStatus.DONE && it.key !in identities }
                        .sortedByDescending { maxOf(it.updatedAt, it.addedAt) }.take(20).map { item ->
                            Card(item.key, values(item.key, item.title, item.posterPath, link("detail", item.type.path, item.id.toString()),
                                maxOf(item.updatedAt, item.addedAt), if (item.type == MediaType.MOVIE) 0 else 1))
                        }
                    Publication((resumed + watching.map(::detailCard)).take(20), (resumed + library).take(20))
                }
            }.collectLatest { publication ->
                // A launcher may support only one surface; failure of one must not suppress the other.
                for (publish in listOf<() -> Unit>(
                    { sync(TvContract.WatchNextPrograms.CONTENT_URI, publication?.next.orEmpty()) },
                    { syncChannel(publication?.channel) },
                )) try { publish() }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("WatchHubNext", "TV provider unavailable", e)
                }
            }
        }
    }

    private fun identity(e: WatchEntry): String? = e.sourceRef?.let { "${it.query.type.path}:${it.query.tmdbId}" }
        ?: e.animeRef?.takeIf { it.detailId > 0 }?.let { "${it.detailType}:${it.detailId}" }
        ?: e.fourURef?.let { ref -> ref.detailId?.let { "${ref.detailType}:$it" } }
        ?: e.tmdbId?.takeIf { !e.isEpisode }?.let { "movie:$it" }

    private fun link(host: String, vararg path: String): String {
        val uri = Uri.Builder().scheme("watchhub").authority(host).apply { path.forEach { appendPath(it) } }.build()
        return Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME)
    }

    private fun values(key: String, title: String, image: String?, intent: String, at: Long, type: Int) = ContentValues().apply {
        put("internal_provider_id", key); put("title", title); put("type", type)
        put("last_engagement_time_utc_millis", at); put("searchable", 1); put("intent_uri", intent)
        if (!image.isNullOrBlank()) put("poster_art_uri", if (image.startsWith("http")) image else "https://image.tmdb.org/t/p/w500$image")
    }

    private fun resumeCard(e: WatchEntry) = Card("resume:${e.key}", values("resume:${e.key}", e.title, e.image,
        link("resume", e.key), e.updatedAt, if (e.isEpisode && e.animeRef?.type != "MOVIE") 3 else 0).apply {
        put("watch_next_type", 0)
        put("last_playback_position_millis", e.positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        if (e.durationMs > 0) put("duration_millis", e.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        e.subtitle?.let { put("description", it); if (e.isEpisode) put("episode_title", it) }
        e.sourceRef?.query?.let { q -> q.season?.let { put("season_display_number", it.toString()) }; q.episode?.let { put("episode_display_number", it.toString()) } }
        e.animeRef?.let { put("season_display_number", it.season.toString()); put("episode_display_number", it.episode) }
    })

    private fun detailCard(item: WatchItem): Card {
        val next = if (item.type != MediaType.MOVIE) item.nextEpisode() else null
        return Card(item.key, values(item.key, item.title, item.posterPath, link("detail", item.type.path, item.id.toString()),
            maxOf(item.updatedAt, item.addedAt), if (item.type == MediaType.MOVIE) 0 else 3).apply {
            put("watch_next_type", if (next == null) 3 else 1)
            next?.let { put("season_display_number", it.season.toString()); put("episode_display_number", it.episode.toString()); put("episode_title", "الموسم ${it.season} · الحلقة ${it.episode}") }
        })
    }

    private fun sync(table: Uri, cards: List<Card>, channelId: Long? = null) {
        val resolver = context.contentResolver
        val own = mutableMapOf<String, Long>()
        resolver.query(table, arrayOf("_id", "internal_provider_id", "package_name"), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(2) == context.packageName) {
                val key = c.getString(1) ?: continue
                own.put(key, c.getLong(0))?.let { resolver.delete(ContentUris.withAppendedId(table, it), null, null) }
            }
        }
        for (card in cards) {
            val value = ContentValues(card.values)
            if (channelId != null) { value.remove("watch_next_type"); value.remove("last_engagement_time_utc_millis"); value.put("channel_id", channelId) }
            val id = own.remove(card.key)
            if (id == null) resolver.insert(table, value) else resolver.update(ContentUris.withAppendedId(table, id), value, null, null)
        }
        own.values.forEach { resolver.delete(ContentUris.withAppendedId(table, it), null, null) }
    }

    private fun syncChannel(cards: List<Card>?) {
        val resolver = context.contentResolver
        var id = channelId(context)
        if (cards == null) { id?.let { resolver.delete(ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, it), null, null) }; return }
        if (id == null) {
            val row = resolver.insert(TvContract.Channels.CONTENT_URI, ContentValues().apply {
                put("type", TvContract.Channels.TYPE_PREVIEW); put("display_name", "Watch Hub")
                put("internal_provider_id", CHANNEL)
                put("app_link_intent_uri", Intent(context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME))
            }) ?: return
            id = ContentUris.parseId(row)
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            try {
                icon.setBounds(0, 0, 256, 256); icon.draw(Canvas(bitmap))
                resolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            TvContract.requestChannelBrowsable(context, id)
        }
        sync(TvContract.PreviewPrograms.CONTENT_URI, cards, id)
    }

    companion object {
        private const val CHANNEL = "watchhub-home"
        private fun channelId(context: Context): Long? {
            context.contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf("_id", "internal_provider_id", "package_name"), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(2) == context.packageName && c.getString(1) == CHANNEL) return c.getLong(0)
            }
            return null
        }
        fun requestChannel(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val id = channelId(context)
                    withContext(Dispatchers.Main) {
                        if (id == null) Toast.makeText(context, "فعّل عرض الأعمال أولاً ثم حاول مرة ثانية", Toast.LENGTH_SHORT).show()
                        else context.startActivity(Intent(TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContract.EXTRA_CHANNEL_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "واجهة التلفزيون لا تدعم إظهار الصف بهذه الطريقة", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }
}
