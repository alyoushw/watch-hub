package com.alyoushw.watchhub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    /** Animeify's stable source reference; never a temporary CDN URL. */
    val animeRef: AnimeEpisodeRef? = null,
    val sourceRef: WatchSourceRef? = null,
    val fourURef: FourURef? = null,
) {
    val isLive: Boolean get() = kind == LIVE
    val isEpisode: Boolean get() = kind == EPISODE || kind == ANIME

    /** 0..1, or 0 when the length is unknown (live). */
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Watched to the end (or near enough): no point resuming. */
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.95

    companion object {
        const val LIVE = "live"
        const val MOVIE = "movie"
        const val EPISODE = "episode"
        const val ANIME = "anime_episode"

        fun keyOf(account: XtreamAccount, kind: String, id: String) = "${account.id}:$kind:$id"

        fun live(item: XtreamItem, account: XtreamAccount) =
            WatchEntry(keyOf(account, LIVE, item.id), LIVE, item.id, item.name, image = item.image, accountId = account.id,
                fourURef = if (account.id == FourU.account.id) FourURef("live", item.id) else null)

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

    private fun prefKey(): String = KEY_ITEMS

    private fun load(): List<WatchEntry> =
        runCatching { json.decodeFromString(serializer, prefs.getString(prefKey(), "[]") ?: "[]") }.getOrDefault(emptyList())

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<WatchEntry>> = _entries.asStateFlow()

    /** Call after the Xtream login changes. */
    fun reload() {
        _entries.value = load()
    }

    /**
     * Written straight to disk (commit, not apply): the player saves just before the user may
     * leave the app, and a write still queued then could be lost if Android closes the app.
     */
    private fun write(list: List<WatchEntry>) {
        prefs.edit().putString(prefKey(), json.encodeToString(serializer, list)).commit()
    }

    fun get(key: String): WatchEntry? = _entries.value.firstOrNull { it.key == key }

    /**
     * A source-independent identity for the same film or episode, so the saved position carries
     * across providers and across Xtream accounts (Voltra, Strong, 4U, web sources…). Built from
     * the TMDB id when known, otherwise the normalized title, plus the season/episode for a series.
     * Live channels have none (nothing to resume).
     */
    private fun contentKeyOf(e: WatchEntry): String? {
        if (e.isLive) return null
        if (e.isEpisode) {
            val s = Regex("الموسم\\s*(\\d+)").find(e.subtitle.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            val ep = Regex("الحلقة\\s*(\\d+)").find(e.subtitle.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (s == null || ep == null) return null
            val tmdb = e.tmdbId
            val base = if (tmdb != null) "tmdb:$tmdb" else {
                val name = XtreamApi.normalizeTitle(e.title)
                if (name.isBlank()) return null else "name:$name"
            }
            return "tv:$base:s$s:e$ep"
        }
        if (e.kind == WatchEntry.MOVIE) {
            val tmdb = e.tmdbId
            if (tmdb != null) return "movie:tmdb:$tmdb"
            val name = XtreamApi.normalizeTitle(e.title)
            return if (name.isBlank()) null else "movie:name:$name"
        }
        return null
    }

    /**
     * The furthest position already watched for the same film/episode from ANY source, so switching
     * provider or account keeps playing from where the user stopped. 0 when there is nothing to resume.
     */
    fun crossResumePosition(e: WatchEntry): Long {
        val ck = contentKeyOf(e) ?: return 0L
        return _entries.value.asSequence()
            .filter { !it.isLive && !it.finished && it.positionMs > 10_000 && contentKeyOf(it) == ck }
            .maxOfOrNull { it.positionMs } ?: 0L
    }

    /**
     * Called after every change made on this device, for the account sync: the entry (or the
     * removed key), and whether it is worth sending at once (start, pause, stop) rather than
     * with the next periodic update.
     */
    var onChange: ((entry: WatchEntry?, key: String, removedAt: Long?, important: Boolean) -> Unit)? = null

    /** Records [entry] as the latest thing watched, keeping a saved position unless a new one is given. */
    fun save(entry: WatchEntry, important: Boolean = false) {
        val merged: WatchEntry
        synchronized(this) {
            val old = get(entry.key)
            merged = entry.copy(
                positionMs = if (entry.positionMs > 0 || entry.durationMs > 0) entry.positionMs else old?.positionMs ?: 0,
                durationMs = if (entry.durationMs > 0) entry.durationMs else old?.durationMs ?: 0,
                updatedAt = System.currentTimeMillis(),
            )
            val next = (listOf(merged) + _entries.value.filter { it.key != entry.key }).take(MAX)
            _entries.value = next
            write(next)
            forgetRemoval(entry.key)
        }
        onChange?.invoke(merged, merged.key, null, important)
    }

    fun remove(key: String) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val next = _entries.value.filter { it.key != key }
            _entries.value = next
            write(next)
            rememberRemoval(key, now)
        }
        onChange?.invoke(null, key, now, true)
    }

    // ---- removals are remembered for a while, so a copy on another device cannot bring them back

    private val removalsSerializer = MapSerializer(String.serializer(), Long.serializer())

    private fun removals(): MutableMap<String, Long> = runCatching {
        json.decodeFromString(removalsSerializer, prefs.getString(KEY_REMOVED, "{}") ?: "{}").toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun storeRemovals(map: Map<String, Long>) {
        val kept = map.entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value }
        prefs.edit().putString(
            KEY_REMOVED,
            json.encodeToString(removalsSerializer, kept),
        ).commit()
    }

    private fun rememberRemoval(key: String, at: Long) = storeRemovals(removals().apply { put(key, at) })

    private fun forgetRemoval(key: String) {
        val r = removals()
        if (r.remove(key) != null) storeRemovals(r)
    }

    /** Removed keys and when, for the first sync after signing in. */
    fun removedKeys(): Map<String, Long> = removals()

    /**
     * A change from another device (through the account): kept when newer than what this
     * device has. [entry] null means it was removed at [updatedAt]. Nothing is sent back.
     */
    @Synchronized
    fun applyRemote(key: String, entry: WatchEntry?, updatedAt: Long): Boolean {
        val local = get(key)
        val removedHere = removals()[key]
        if (local != null && local.updatedAt >= updatedAt) return false
        if (local == null && removedHere != null && removedHere >= updatedAt) return false
        val others = _entries.value.filter { it.key != key }
        val next = if (entry == null) {
            if (local == null) return false
            rememberRemoval(key, updatedAt)
            others
        } else {
            forgetRemoval(key)
            (others + entry.copy(updatedAt = updatedAt)).sortedByDescending { it.updatedAt }.take(MAX)
        }
        _entries.value = next
        write(next)
        return true
    }

    /** Puts [from]'s history aside (or drops it when [discard]) and brings back [to]'s (empty if none). */
    @Synchronized
    fun switchProfile(from: String, to: String, discard: Boolean) {
        val e = prefs.edit()
        for (k in listOf(KEY_ITEMS, KEY_REMOVED, KEY_CW_HIDDEN)) {
            if (discard) e.remove("$k@$from") else e.putString("$k@$from", prefs.getString(k, null))
            e.putString(k, prefs.getString("$k@$to", null))
            e.remove("$k@$to")
        }
        e.commit()
        _entries.value = load()
        _continueHidden.value = loadHidden()
    }

    /** Forgets everything watched in this profile. */
    @Synchronized
    fun clearAll() {
        prefs.edit().remove(KEY_ITEMS).remove(KEY_REMOVED).remove(KEY_CW_HIDDEN).commit()
        _entries.value = emptyList()
        _continueHidden.value = emptyMap()
    }

    /** Re-points entries after account ids changed (old id → new id). */
    @Synchronized
    fun remapAccounts(changes: Map<String, String>) {
        if (changes.isEmpty()) return
        var touched = false
        val next = _entries.value.map { e ->
            val to = changes[e.accountId] ?: return@map e
            touched = true
            e.copy(accountId = to, key = to + e.key.removePrefix(e.accountId.orEmpty()))
        }.distinctBy { it.key }
        if (touched) {
            _entries.value = next
            write(next)
        }
    }

    // ---- hidden from "تابع المشاهدة" (the series/film stays in history, just leaves the row) ----

    private fun loadHidden(): Map<String, Long> = runCatching {
        json.decodeFromString(removalsSerializer, prefs.getString(KEY_CW_HIDDEN, "{}") ?: "{}")
    }.getOrDefault(emptyMap())

    private val _continueHidden = MutableStateFlow(loadHidden())

    /** Series/films the user dropped from "تابع المشاهدة": group key → when hidden. */
    val continueHidden: StateFlow<Map<String, Long>> = _continueHidden.asStateFlow()

    /** One key per series (so all its episodes share it), else the entry's own key. */
    private fun continueGroupKey(e: WatchEntry): String =
        if (e.isEpisode && e.seriesId != null) "${e.accountId}:${e.seriesId}" else e.key

    /**
     * Drops the whole series (or film) from "تابع المشاهدة" without touching the watch history, so
     * the series page still shows which episodes were watched. Watching a newer episode brings it
     * back on its own.
     */
    fun hideFromContinue(e: WatchEntry) {
        val gk = continueGroupKey(e)
        val now = System.currentTimeMillis()
        saveHidden(_continueHidden.value + (gk to now))
        onHidden?.invoke(gk, now)
    }

    /** Set by the account sync: a card removed here leaves «تابع المشاهدة» on every device. */
    var onHidden: ((String, Long) -> Unit)? = null

    /** Removals made on another device (group key → when); the later time wins. */
    @Synchronized
    fun mergeHidden(remote: Map<String, Long>) {
        val cur = _continueHidden.value
        val changed = remote.filter { (k, at) -> (cur[k] ?: 0) < at }
        if (changed.isNotEmpty()) saveHidden(cur + changed)
    }

    private fun saveHidden(next: Map<String, Long>) {
        val kept = next.entries.sortedByDescending { it.value }.take(300).associate { it.key to it.value }
        _continueHidden.value = kept
        prefs.edit().putString(KEY_CW_HIDDEN, json.encodeToString(removalsSerializer, kept)).commit()
    }

    /** For the "تابع المشاهدة" row: one card per series (its latest episode), finished films left out. */
    fun continueList(): List<WatchEntry> {
        val hidden = _continueHidden.value
        val seenSeries = mutableSetOf<String>()
        return _entries.value.filter { e ->
            val keep = when {
                e.isEpisode && e.seriesId != null -> seenSeries.add("${e.accountId}:${e.seriesId}")
                e.kind == WatchEntry.MOVIE -> !e.finished
                else -> true
            }
            // Hidden stays hidden until something newer than the moment it was hidden is watched.
            keep && (hidden[continueGroupKey(e)]?.let { e.updatedAt > it } ?: true)
        }
    }

    private companion object {
        const val KEY_ITEMS = "items_v2"
        const val KEY_REMOVED = "removed_v2"
        const val KEY_CW_HIDDEN = "continue_hidden_v1"
        const val MAX = 80
    }
}
