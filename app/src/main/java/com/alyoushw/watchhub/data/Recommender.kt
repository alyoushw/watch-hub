package com.alyoushw.watchhub.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A suggestion with the reason it fits the user. */
data class Pick(val item: SearchResult, val reason: String, val score: Double)

/** "خلصت X؟": a title the user just finished, and what to watch next because of it. */
data class FinishedNext(val title: String, val key: String, val picks: List<Pick>)

/**
 * Learns the user's taste from what they watch (the watch history: finished films, series watched
 * episode after episode, things left half way) and their list, then suggests titles with a reason:
 * "لأنك خلصت «X» · دراما، غموض · كوري". Built on TMDB (each watched title's genres, country,
 * creators and TMDB's own "if you liked this" lists). "مو لي" teaches it what to show less.
 */
class Recommender(
    context: Context,
    private val api: TmdbApi,
    private val history: WatchHistory,
    private val store: LocalStore,
) {
    private val prefs = context.getSharedPreferences("recommender", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _picks = MutableStateFlow<List<Pick>?>(null)
    val picks: StateFlow<List<Pick>?> = _picks.asStateFlow()

    private val _finished = MutableStateFlow<FinishedNext?>(null)
    val finished: StateFlow<FinishedNext?> = _finished.asStateFlow()

    private var builtFor = ""

    /** The user's favourite genres, strongest first (for "ملخص مشاهداتك"). */
    var topGenres: List<String> = emptyList()
        private set

    /** One title the user watched, with how much it tells about their taste. */
    private class Seed(val type: MediaType, val id: Int, var title: String, val weight: Double, val finishedAt: Long?)

    /** Builds (or reuses) the suggestions; [force] rebuilds even if nothing changed. */
    suspend fun refresh(force: Boolean = false) = lock.withLock {
        val entries = history.entries.value
        val list = store.data.value.watchlist
        val signature = entries.joinToString { "${it.key}:${it.finished}" }.hashCode().toString() + ":" + list.size + ":" + dismissed().size
        if (!force && signature == builtFor && _picks.value != null) return@withLock
        runCatching { build(entries, list) }
        builtFor = signature
    }

    private suspend fun build(entries: List<WatchEntry>, watchlist: List<WatchItem>) {
        // ---- seeds: what the user watched, and how much ----
        val seeds = mutableListOf<Seed>()
        val bySeries = entries.filter { it.kind == WatchEntry.EPISODE && it.seriesId != null }.groupBy { "${it.accountId}:${it.seriesId}" }
        for ((_, eps) in bySeries.entries.take(10)) {
            val first = eps.first()
            val done = eps.count { it.finished }
            val id = resolve(MediaType.TV, first.title) ?: continue
            // Many episodes watched: a strong taste signal; one episode left: a weak one.
            val weight = when {
                done >= 5 -> 3.0
                done >= 2 -> 2.0
                done == 1 -> 1.0
                else -> 0.4
            }
            seeds += Seed(MediaType.TV, id, XtreamApi.normalizeTitle(first.title).ifBlank { first.title }, weight, eps.filter { it.finished }.maxOfOrNull { it.updatedAt })
        }
        for (m in entries.filter { it.kind == WatchEntry.MOVIE }.take(10)) {
            val id = m.tmdbId ?: resolve(MediaType.MOVIE, m.title) ?: continue
            val weight = if (m.finished) 2.0 else if (m.progress > 0.5) 1.0 else 0.3
            seeds += Seed(MediaType.MOVIE, id, m.title, weight, m.updatedAt.takeIf { m.finished })
        }
        for (w in watchlist.filter { it.type != MediaType.ANIME }.sortedByDescending { it.addedAt }.take(8)) {
            val weight = when (w.status) {
                WatchStatus.DONE -> 2.0
                WatchStatus.WATCHING -> 1.5
                WatchStatus.PLANNED -> 0.7
            }
            seeds += Seed(w.type, w.id, w.title, weight, null)
        }
        val unique = seeds.groupBy { "${it.type.path}:${it.id}" }.map { (_, s) -> s.maxBy { it.weight } }.sortedByDescending { it.weight }.take(12)
        if (unique.isEmpty()) {
            _picks.value = emptyList()
            _finished.value = null
            return
        }

        // ---- taste: genres, countries and their weight; each seed's "if you liked this" ----
        val details = coroutineScope {
            unique.map { s -> async { runCatching { s to api.details(s.type, s.id) }.getOrNull() } }.awaitAll().filterNotNull()
        }
        val genreW = mutableMapOf<Int, Double>()
        val countryW = mutableMapOf<String, Double>()
        val disliked = dislikedGenres()
        for ((s, d) in details) {
            // Its proper name ("Dear X"), not the account's ("AR-AS-S: Dear X (2025) (KR)").
            d.displayTitle.takeIf { it.isNotBlank() }?.let { s.title = it }
            d.genres.forEach { g -> genreW[g.id] = (genreW[g.id] ?: 0.0) + s.weight }
            (d.originCountry.firstOrNull() ?: d.productionCountries.firstOrNull()?.code)?.let { c -> countryW[c] = (countryW[c] ?: 0.0) + s.weight }
        }
        val genreTotal = genreW.values.sum().coerceAtLeast(1.0)
        topGenres = genreW.entries.sortedByDescending { it.value }.mapNotNull { GenreNames[it.key] }.distinct().take(4)
        val countryTotal = countryW.values.sum().coerceAtLeast(1.0)

        val seen = (unique.map { "${it.type.path}:${it.id}" } + watchlist.map { "${it.type.path}:${it.id}" }).toSet()
        val skip = dismissed()

        // Candidates: each seed's recommendations, credited to the seed.
        val cands = mutableMapOf<String, Triple<SearchResult, MutableList<Seed>, Double>>()
        for ((s, d) in details) {
            d.recommendations?.results.orEmpty().take(20).forEach { r0 ->
                val r = if (r0.mediaType == null) r0.copy(mediaType = s.type.path) else r0
                val key = "${r.mediaType}:${r.id}"
                if (key in seen || key in skip || r.type == null || r.posterPath == null) return@forEach
                val old = cands[key]
                if (old == null) cands[key] = Triple(r, mutableListOf(s), s.weight) else {
                    old.second += s
                    cands[key] = Triple(old.first, old.second, old.third + s.weight)
                }
            }
        }
        // Plus well-rated titles of the favourite genre and country.
        val topGenre = genreW.entries.filter { it.key !in disliked }.maxByOrNull { it.value }?.key
        val topCountry = countryW.maxByOrNull { it.value }?.key
        val topType = unique.groupBy { it.type }.maxByOrNull { (_, v) -> v.sumOf { it.weight } }?.key ?: MediaType.TV
        if (topGenre != null) {
            runCatching {
                val params = mutableMapOf("with_genres" to "$topGenre", "sort_by" to "vote_average.desc", "vote_count.gte" to "150")
                topCountry?.let { params["with_origin_country"] = it }
                api.discover(topType, params, streamableOnly = false)
            }.getOrNull().orEmpty().forEach { r ->
                val key = "${r.mediaType}:${r.id}"
                if (key in seen || key in skip || key in cands || r.posterPath == null) return@forEach
                cands[key] = Triple(r, mutableListOf(), 0.0)
            }
        }

        // ---- score and explain ----
        val scored = cands.values.map { (r, from, seedScore) ->
            val gMatch = r.genreIds.sumOf { (genreW[it] ?: 0.0) / genreTotal }
            val cMatch = r.country?.let { (countryW[it] ?: 0.0) / countryTotal } ?: 0.0
            val bad = r.genreIds.count { it in disliked } * 0.8
            val rating = ((r.voteAverage ?: 6.0) - 6.0) / 4.0
            val score = seedScore * 1.2 + gMatch * 3 + cMatch * 2 + rating - bad
            Pick(r, reason(r, from, genreW.keys), score)
        }.sortedByDescending { it.score }
        _picks.value = scored.take(20)

        // ---- "خلصت X؟": the latest title finished in the last 3 days ----
        val recent = unique.filter { it.finishedAt != null && System.currentTimeMillis() - it.finishedAt < 3 * 86_400_000L }
            .maxByOrNull { it.finishedAt ?: 0 }
        val ack = prefs.getString(KEY_ACK, null)
        val doneSeed = recent?.takeIf { s ->
            val key = "${s.type.path}:${s.id}"
            if (key == ack) return@takeIf false
            if (s.type == MediaType.MOVIE) return@takeIf true
            // A series counts as finished once its last episode out is watched.
            val d = details.firstOrNull { it.first.id == s.id }?.second ?: return@takeIf false
            val last = d.latestAired() ?: return@takeIf false
            entries.any { e ->
                e.finished && e.kind == WatchEntry.EPISODE &&
                    EpisodeNumber.find(e.subtitle.orEmpty())?.let { m -> m.groupValues[1].toInt() == last.season && m.groupValues[2].toInt() == last.episode } == true
            }
        }
        _finished.value = doneSeed?.let { s ->
            val next = scored.filter { p -> cands["${p.item.mediaType}:${p.item.id}"]?.second?.any { it.id == s.id } == true }.take(3)
                .ifEmpty { scored.take(3) }
            FinishedNext(s.title, "${s.type.path}:${s.id}", next)
        }
    }

    private fun reason(r: SearchResult, from: List<Seed>, liked: Set<Int>): String {
        val parts = mutableListOf<String>()
        from.maxByOrNull { it.weight }?.let { s ->
            parts += if (s.finishedAt != null || s.weight >= 2) "لأنك شفت «${s.title}»" else "يشبه «${s.title}»"
        }
        val genres = r.genreIds.filter { it in liked }.mapNotNull { GenreNames[it] }.distinct().take(2)
        if (genres.isNotEmpty()) parts += genres.joinToString("، ")
        countryLabel(r.country)?.let { parts += it }
        r.voteAverage?.takeIf { it >= 7.5 }?.let { parts += "تقييم ${"%.1f".format(java.util.Locale.US, it)}" }
        if (parts.isEmpty()) parts += "يناسب ذوقك"
        return parts.joinToString(" · ")
    }

    /** TMDB id for a title from the account (by its cleaned-up name); remembered. */
    suspend fun tmdbIdFor(type: MediaType, title: String): Int? = resolve(type, title)

    private suspend fun resolve(type: MediaType, title: String): Int? {
        val name = XtreamApi.normalizeTitle(title)
        if (name.isBlank()) return null
        val key = "id:${type.path}:$name"
        prefs.getInt(key, -1).takeIf { it != -1 }?.let { return it.takeIf { id -> id > 0 } }
        val hit = runCatching { api.search(name).firstOrNull { it.type == type } }.getOrElse { return null }
        prefs.edit().putInt(key, hit?.id ?: 0).apply()
        return hit?.id
    }

    // ---- "مو لي" ----

    private fun dismissed(): Set<String> = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()

    private fun dislikedGenres(): Set<Int> {
        // A genre the user turned down three times or more is shown less.
        return prefs.all.filterKeys { it.startsWith("nope:") }.filter { (it.value as? Int ?: 0) >= 3 }.keys.mapNotNull { it.removePrefix("nope:").toIntOrNull() }.toSet()
    }

    fun notForMe(pick: Pick) {
        val key = "${pick.item.mediaType}:${pick.item.id}"
        val e = prefs.edit().putStringSet(KEY_DISMISSED, dismissed() + key)
        pick.item.genreIds.forEach { g -> e.putInt("nope:$g", prefs.getInt("nope:$g", 0) + 1) }
        e.apply()
        _picks.value = _picks.value?.filter { it != pick }
        _finished.value = _finished.value?.let { f -> f.copy(picks = f.picks.filter { it != pick }) }
    }

    fun acknowledgeFinished() {
        _finished.value?.let { prefs.edit().putString(KEY_ACK, it.key).apply() }
        _finished.value = null
    }

    companion object {
        private const val KEY_DISMISSED = "dismissed"
        private const val KEY_ACK = "ack_finished"
        private val EpisodeNumber = Regex("الموسم (\\d+) · الحلقة (\\d+)")

        /** TMDB genre names (films and series). */
        val GenreNames = mapOf(
            28 to "أكشن", 12 to "مغامرة", 16 to "رسوم متحركة", 35 to "كوميدي", 80 to "جريمة", 99 to "وثائقي",
            18 to "دراما", 10751 to "عائلي", 14 to "فانتازيا", 36 to "تاريخي", 27 to "رعب", 10402 to "موسيقي",
            9648 to "غموض", 10749 to "رومانسي", 878 to "خيال علمي", 53 to "إثارة", 10752 to "حربي", 37 to "غربي",
            10759 to "أكشن ومغامرة", 10762 to "أطفال", 10763 to "أخبار", 10764 to "واقعي", 10765 to "خيال وفانتازيا",
            10766 to "مسلسل طويل", 10767 to "حواري", 10768 to "حرب وسياسة",
        )
    }
}
