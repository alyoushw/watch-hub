package com.alyoushw.watchhub.data

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
enum class MediaType(val path: String, val label: String) {
    MOVIE("movie", "فيلم"),
    TV("tv", "مسلسل"),

    /** Anime from MyAnimeList; ids are MAL ids, not TMDB ids. */
    ANIME("anime", "أنمي");

    companion object {
        fun fromPath(path: String?): MediaType? = entries.firstOrNull { it.path == path }
    }
}

@Serializable
enum class WatchStatus(val label: String) {
    PLANNED("أبي أشوفه"),
    WATCHING("أتابعه"),
    DONE("خلصته"),
}

@Serializable
data class SeasonInfo(val number: Int, val episodes: Int)

@Serializable
data class EpisodeMark(val season: Int, val episode: Int) : Comparable<EpisodeMark> {
    override fun compareTo(other: EpisodeMark): Int =
        compareValuesBy(this, other, { it.season }, { it.episode })
}

@Serializable
data class WatchItem(
    val id: Int,
    val type: MediaType,
    val title: String,
    /** Title used when searching inside the streaming apps (usually English). */
    val searchTitle: String = title,
    val posterPath: String? = null,
    val providerIds: List<Int> = emptyList(),
    val status: WatchStatus = WatchStatus.PLANNED,
    /** Last watched episode; null means nothing watched yet. TV only. */
    val progress: EpisodeMark? = null,
    val seasons: List<SeasonInfo> = emptyList(),
    /** Most recent episode that has aired. TV only. */
    val latestAired: EpisodeMark? = null,
    /** Service key → URL of the title's own page on that service. */
    val directLinks: Map<String, String> = emptyMap(),
    val addedAt: Long = System.currentTimeMillis(),
) {
    val key: String get() = keyOf(type, id)

    companion object {
        fun keyOf(type: MediaType, id: Int) = "${type.path}:$id"
    }
}

private fun WatchItem.sortedSeasons() = seasons.sortedBy { it.number }

/** The episode after [WatchItem.progress], or null when the show is finished. */
fun WatchItem.nextEpisode(): EpisodeMark? {
    val s = sortedSeasons()
    // Episode count unknown (e.g. an anime still airing): just keep counting up.
    if (s.isEmpty()) return EpisodeMark(progress?.season ?: 1, (progress?.episode ?: 0) + 1)
    val p = progress ?: return EpisodeMark(s.first().number, 1)
    val idx = s.indexOfFirst { it.number == p.season }
    if (idx == -1) return EpisodeMark(p.season, p.episode + 1)
    return if (p.episode < s[idx].episodes) {
        EpisodeMark(p.season, p.episode + 1)
    } else {
        s.getOrNull(idx + 1)?.let { EpisodeMark(it.number, 1) }
    }
}

/** The episode before [WatchItem.progress]; null means "nothing watched". */
fun WatchItem.previousEpisode(): EpisodeMark? {
    val p = progress ?: return null
    if (p.episode > 1) return EpisodeMark(p.season, p.episode - 1)
    val s = sortedSeasons()
    val idx = s.indexOfFirst { it.number == p.season }
    return s.getOrNull(idx - 1)?.let { EpisodeMark(it.number, it.episodes) }
}

/** Number of aired episodes that have not been watched yet. */
fun WatchItem.unwatchedAired(): Int {
    val aired = latestAired ?: return 0
    var count = 0
    for (season in sortedSeasons()) {
        if (season.number > aired.season) break
        val airedInSeason = if (season.number == aired.season) aired.episode else season.episodes
        val watched = when {
            progress == null -> 0
            season.number < progress.season -> season.episodes
            season.number == progress.season -> progress.episode
            else -> 0
        }
        count += (airedInSeason - watched).coerceAtLeast(0)
    }
    return count
}

@Serializable
enum class BillingCycle(val label: String) {
    MONTHLY("شهري"),
    YEARLY("سنوي"),
}

@Serializable
data class Subscription(
    val id: String,
    /** Key of a known [StreamingService], or null for a custom one. */
    val serviceKey: String? = null,
    val name: String,
    val price: Double,
    val currency: String = "SAR",
    val cycle: BillingCycle = BillingCycle.MONTHLY,
    /** First known renewal date, as epoch day. */
    val renewalEpochDay: Long,
) {
    val monthlyCost: Double get() = if (cycle == BillingCycle.MONTHLY) price else price / 12.0

    /** The next renewal on or after [today], stepping whole cycles from the stored date. */
    fun nextRenewal(today: LocalDate = LocalDate.now()): LocalDate {
        val start = LocalDate.ofEpochDay(renewalEpochDay)
        var n = 0L
        var date = start
        while (date.isBefore(today)) {
            n++
            date = if (cycle == BillingCycle.MONTHLY) start.plusMonths(n) else start.plusYears(n)
        }
        return date
    }
}

@Serializable
data class StoreData(
    val watchlist: List<WatchItem> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    /** The user's saved "taste" presets for search. */
    val savedFilters: List<SavedFilter> = emptyList(),
)
