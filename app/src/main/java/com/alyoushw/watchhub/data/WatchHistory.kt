package com.alyoushw.watchhub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Something played from the Xtream account (a channel, a film or an episode) and how far
 * the user got, for "تابع المشاهدة" and for resuming where they stopped.
 */
@Serializable
data class WatchEntry(
    /** "<account>:live:123", "<account>:movie:900" or "<account>:episode:5001". */
    val key: String,
    val kind: String,
    val itemId: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val ext: String? = null,
    val seriesId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
    /** Which Xtream account it was watched on (see SettingsStore.xtreamAccounts). */
    val accountId: String? = null,
    /** The TMDB title, when known, for "افتح صفحة الفيلم". */
    val tmdbId: Int? = null,
) {
    val isLive: Boolean get() = kind == LIVE

    /** 0..1, or 0 when the length is unknown (live). */
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Watched to the end (or near enough): no point resuming. */
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.95

    companion object {
        const val LIVE = "live"
        const val MOVIE = "movie"
        const val EPISODE = "episode"

        fun keyOf(account: XtreamAccount, kind: String, id: String) = "${account.id}:$kind:$id"

        fun live(item: XtreamItem, account: XtreamAccount) =
            WatchEntry(keyOf(account, LIVE, item.id), LIVE, item.id, item.name, image = item.image, accountId = account.id)

        fun movie(item: XtreamItem, account: XtreamAccount, tmdbId: Int? = null) = WatchEntry(
            keyOf(account, MOVIE, item.id), MOVIE, item.id, item.name, image = item.image, ext = item.extension,
            accountId = account.id, tmdbId = tmdbId ?: item.tmdb?.toIntOrNull(),
        )

        fun episode(account: XtreamAccount, seriesId: String, seriesName: String, cover: String?, ep: XtreamEpisode) = WatchEntry(
            key = keyOf(account, EPISODE, ep.id),
            kind = EPISODE,
            itemId = ep.id,
            title = seriesName,
            subtitle = "الموسم ${ep.season} · الحلقة ${ep.number}" + ep.title.takeIf { !it.startsWith("الحلقة") }?.let { " · $it" }.orEmpty(),
            image = ep.image ?: cover,
            ext = ep.extension,
            seriesId = seriesId,
            accountId = account.id,
        )
    }
}

/** Watch history on the device, newest first, across all Xtream accounts. */
class WatchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WatchEntry.serializer())

    private fun prefKey(): String = "items_v2"

    private fun load(): List<WatchEntry> =
        runCatching { json.decodeFromString(serializer, prefs.getString(prefKey(), "[]") ?: "[]") }.getOrDefault(emptyList())

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<WatchEntry>> = _entries.asStateFlow()

    /** Call after the Xtream login changes. */
    fun reload() {
        _entries.value = load()
    }

    fun get(key: String): WatchEntry? = _entries.value.firstOrNull { it.key == key }

    /** Records [entry] as the latest thing watched, keeping a saved position unless a new one is given. */
    @Synchronized
    fun save(entry: WatchEntry) {
        val old = get(entry.key)
        val merged = entry.copy(
            positionMs = if (entry.positionMs > 0 || entry.durationMs > 0) entry.positionMs else old?.positionMs ?: 0,
            durationMs = if (entry.durationMs > 0) entry.durationMs else old?.durationMs ?: 0,
            updatedAt = System.currentTimeMillis(),
        )
        val next = (listOf(merged) + _entries.value.filter { it.key != entry.key }).take(MAX)
        _entries.value = next
        prefs.edit().putString(prefKey(), json.encodeToString(serializer, next)).apply()
    }

    fun remove(key: String) {
        val next = _entries.value.filter { it.key != key }
        _entries.value = next
        prefs.edit().putString(prefKey(), json.encodeToString(serializer, next)).apply()
    }

    /** For the "تابع المشاهدة" row: one card per series (its latest episode), finished films left out. */
    fun continueList(): List<WatchEntry> {
        val seenSeries = mutableSetOf<String>()
        return _entries.value.filter { e ->
            when {
                e.kind == WatchEntry.EPISODE && e.seriesId != null -> seenSeries.add("${e.accountId}:${e.seriesId}")
                e.kind == WatchEntry.MOVIE -> !e.finished
                else -> true
            }
        }
    }

    private companion object {
        const val MAX = 80
    }
}
