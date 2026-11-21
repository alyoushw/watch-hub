package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Any error from a data source, with a message ready to show the user. */
open class ApiException(message: String) : Exception(message)

class TmdbException(message: String) : ApiException(message)

@Serializable
data class SearchResponse(
    val results: List<SearchResult> = emptyList(),
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0,
)

@Serializable
private data class AlternativeTitle(val title: String = "")

@Serializable
private data class AlternativeTitles(
    val results: List<AlternativeTitle> = emptyList(),
    val titles: List<AlternativeTitle> = emptyList(),
)

/** Where a list of titles comes from, so it can be fetched page by page ("عرض الكل"). */
@Serializable
sealed interface RowSource {
    @Serializable
    data class Discover(
        val type: MediaType,
        val params: Map<String, String>,
        val streamableOnly: Boolean = true,
    ) : RowSource

    @Serializable
    data class Trending(val type: MediaType) : RowSource
}

@Serializable
data class NamedId(val id: Int, val name: String = "")

@Serializable
private data class NamedIdList(val results: List<NamedId> = emptyList())

@Serializable
data class SearchResult(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    val popularity: Double? = null,
    /** The role, when this result comes from an actor's list of works. */
    val character: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
) {
    /** Country code to show for this title: TMDB's origin country, else guessed from the language. */
    val country: String? get() = originCountry.firstOrNull() ?: countryFromLanguage(originalLanguage)

    val type: MediaType? get() = MediaType.fromPath(mediaType)
    val displayTitle: String get() = title ?: name ?: ""
    val year: String? get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }
}

@Serializable
data class TmdbGenre(val id: Int = 0, val name: String = "")

@Serializable
data class SeasonDto(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
data class EpisodeGroupSummary(
    val id: String = "", val name: String = "", val type: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("group_count") val groupCount: Int = 0,
)
@Serializable
data class EpisodeGroupList(val results: List<EpisodeGroupSummary> = emptyList())
@Serializable
data class GroupedEpisode(
    val id: Int = 0,
    @SerialName("season_number") val season: Int = 0,
    @SerialName("episode_number") val episode: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
)
@Serializable
data class EpisodeGroupBlock(val order: Int = 0, val episodes: List<GroupedEpisode> = emptyList())
@Serializable
data class EpisodeGroupDetails(val groups: List<EpisodeGroupBlock> = emptyList())
data class EpisodePart(val number: Int, val episodes: Int, val year: Int?)
data class EpisodeParts(val name: String, val parts: List<EpisodePart>)

@Serializable
data class Named(val name: String = "")

@Serializable
data class CountryDto(@SerialName("iso_3166_1") val code: String = "", val name: String = "")

@Serializable
data class Person(
    val id: Int = 0,
    val name: String = "",
    val character: String? = null,
    val job: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class ExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
data class Credits(val cast: List<Person> = emptyList(), val crew: List<Person> = emptyList())

/** A series' cast over all its seasons (TMDB keeps many series' casts only here). */
@Serializable
data class AggregateCredits(val cast: List<AggregatePerson> = emptyList())

@Serializable
data class AggregatePerson(
    val id: Int = 0,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val roles: List<Role> = emptyList(),
    @SerialName("total_episode_count") val episodes: Int = 0,
    val order: Int = 999,
)

@Serializable
data class Role(val character: String? = null)

@Serializable
data class Video(
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val name: String = "",
    val official: Boolean = false,
)

@Serializable
data class Videos(val results: List<Video> = emptyList())

@Serializable
data class AuthorDetails(val rating: Double? = null)

@Serializable
data class Review(
    val author: String = "",
    val content: String = "",
    @SerialName("author_details") val authorDetails: AuthorDetails? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class Reviews(val results: List<Review> = emptyList())

@Serializable
data class EpisodeRef(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
data class ProviderDto(
    @SerialName("provider_id") val id: Int,
    @SerialName("provider_name") val name: String = "",
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("display_priority") val displayPriority: Int = 999,
)

@Serializable
data class CombinedCredits(val cast: List<SearchResult> = emptyList(), val crew: List<SearchResult> = emptyList())

@Serializable
data class PersonDetails(
    val id: Int = 0,
    val name: String = "",
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val department: String? = null,
    @SerialName("also_known_as") val alsoKnownAs: List<String> = emptyList(),
    @SerialName("combined_credits") val credits: CombinedCredits? = null,
)

@Serializable
data class ProviderList(val results: List<ProviderDto> = emptyList())

@Serializable
data class RegionProviders(
    val link: String? = null,
    val flatrate: List<ProviderDto> = emptyList(),
    val free: List<ProviderDto> = emptyList(),
    val ads: List<ProviderDto> = emptyList(),
    val rent: List<ProviderDto> = emptyList(),
    val buy: List<ProviderDto> = emptyList(),
) {
    /** Providers included in a subscription or free to watch. */
    val streaming: List<ProviderDto> get() = (flatrate + free + ads).distinctBy { it.id }
    val paid: List<ProviderDto> get() = (rent + buy).distinctBy { it.id }
}

@Serializable
data class WatchProvidersDto(val results: Map<String, RegionProviders> = emptyMap())

@Serializable
data class Details(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val tagline: String? = null,
    val status: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("production_countries") val productionCountries: List<CountryDto> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    val networks: List<Named> = emptyList(),
    @SerialName("created_by") val createdBy: List<Named> = emptyList(),
    val credits: Credits? = null,
    @SerialName("aggregate_credits") val aggregateCredits: AggregateCredits? = null,
    @SerialName("external_ids") val externalIds: ExternalIds? = null,
    /** Movies carry the IMDb id directly; series have it under external_ids. */
    @SerialName("imdb_id") val imdbId: String? = null,
    val videos: Videos? = null,
    val reviews: Reviews? = null,
    val recommendations: SearchResponse? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val seasons: List<SeasonDto> = emptyList(),
    @SerialName("last_episode_to_air") val lastEpisodeToAir: EpisodeRef? = null,
    @SerialName("next_episode_to_air") val nextEpisodeToAir: EpisodeRef? = null,
    @SerialName("watch/providers") val watchProviders: WatchProvidersDto? = null,
) {
    val displayTitle: String get() = title ?: name ?: ""
    val year: String? get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }

    fun seasonInfo(): List<SeasonInfo> = seasons
        .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
        .map { SeasonInfo(it.seasonNumber, it.episodeCount) }

    fun latestAired(): EpisodeMark? = lastEpisodeToAir
        ?.takeIf { it.seasonNumber > 0 && it.episodeNumber > 0 }
        ?.let { EpisodeMark(it.seasonNumber, it.episodeNumber) }

    fun providersFor(region: String): RegionProviders? = watchProviders?.results?.get(region)
}

/** Minimal client for The Movie Database (TMDB) v3 API. */
class TmdbApi(private val settings: SettingsStore) {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun search(query: String): List<SearchResult> = searchPage(query).results

    private val keywordCache = java.util.concurrent.ConcurrentHashMap<String, List<Int>>()
    private val personCache = java.util.concurrent.ConcurrentHashMap<String, NamedId>()

    /** TMDB keyword ids for a keyword name ("car race"): exact names first, else the closest one. */
    suspend fun keywordIds(term: String): List<Int> = keywordCache[term] ?: run {
        val found = get("search/keyword", mapOf("query" to term), NamedIdList.serializer()).results
        val exact = found.filter { it.name.equals(term, ignoreCase = true) }
        (exact.ifEmpty { found.take(1) }).map { it.id }.also { keywordCache[term] = it }
    }

    /** The best-known actor or director for a name (Arabic or English), or null. */
    suspend fun findPerson(name: String): NamedId? = personCache[name] ?: get(
        "search/person",
        mapOf("query" to name, "language" to "ar", "include_adult" to "false"),
        NamedIdList.serializer(),
    ).results.firstOrNull()?.also { personCache[name] = it }

    suspend fun searchPage(query: String, page: Int = 1): SearchResponse {
        suspend fun one(q: String) = get(
            "search/multi",
            mapOf("query" to q, "language" to "ar", "include_adult" to "false", "page" to "${page.coerceIn(1, 500)}"),
            SearchResponse.serializer(),
        )
        val response = one(query)
        // TMDB matches Arabic letters exactly, so «الحشاشين» typed with ه/ة, ا/أ or ي/ى differently
        // misses. On the first page also try the usual spelling variants and merge them in.
        val variants = if (page == 1 && query.any { it in '\u0600'..'\u06FF' }) arabicVariants(query) - query else emptyList()
        val extra = variants.take(3).flatMap { v -> runCatching { one(v).results }.getOrDefault(emptyList()) }
        var merged = (response.results + extra).filter { it.type != null }.distinctBy { "${it.type}:${it.id}" }
        // Still little for an Arabic name: TMDB may not have its Arabic title. Arabic Wikipedia
        // gives the English name of the film or series, which TMDB knows.
        if (variants.isNotEmpty() && merged.size < 3) {
            val english = runCatching { englishNameFromWikipedia(query) }.getOrNull()
            if (!english.isNullOrBlank()) {
                val found = runCatching { one(english).results }.getOrDefault(emptyList()).filter { it.type != null }
                merged = (found + merged).distinctBy { "${it.type}:${it.id}" }
            }
        }
        return response.copy(results = merged, totalResults = maxOf(response.totalResults, merged.size))
    }

    private suspend fun englishNameFromWikipedia(arabic: String): String? = withContext(Dispatchers.IO) {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("ar.wikipedia.org").addPathSegments("w/api.php")
            .addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("generator", "search").addQueryParameter("gsrsearch", arabic).addQueryParameter("gsrlimit", "1")
            .addQueryParameter("prop", "langlinks").addQueryParameter("lllang", "en")
            .build()
        val body = Net.streams.newCall(Request.Builder().url(url).header("User-Agent", "WatchHub/1.0").build()).execute()
            .use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
        val pages = (json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject)
            ?.get("query")?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("pages") as? kotlinx.serialization.json.JsonObject ?: return@withContext null
        val links = pages.values.firstOrNull()?.let { it as? kotlinx.serialization.json.JsonObject }?.get("langlinks") as? kotlinx.serialization.json.JsonArray
        val title = (links?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.get("*")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        // «Inception (film)» → «Inception».
        title?.replace(Regex("""\s*\([^)]*\)\s*$"""), "")?.trim()
    }

    private fun arabicVariants(q: String): List<String> {
        val words = q.trim().split(Regex("\\s+"))
        fun map(f: (String) -> String) = words.joinToString(" ") { f(it) }
        val plain = q.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
        return listOf(
            plain,
            map { w -> if (w.endsWith("ه")) w.dropLast(1) + "ة" else w },
            map { w -> if (w.endsWith("ي")) w.dropLast(1) + "ى" else w },
            map { w -> if (w.startsWith("ا") && w.length > 2 && !w.startsWith("ال")) "أ" + w.drop(1) else w },
        ).filter { it.isNotBlank() }.distinct()
    }

    /** Animeify/MAL IDs cannot be used here: resolve names against TMDB's own catalogue. */
    suspend fun animeDetailsMatches(anime: AnimeifyTitle): List<SearchResult> {
        fun key(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
            .lowercase(java.util.Locale.ROOT).replace(Regex("[\\p{M}]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
        fun seriesName(value: String): String = if (anime.type == "MOVIE") value else value.replace(
            Regex("""(?i)\s+(?:(?:\d+(?:st|nd|rd|th)\s+)?season(?:\s+\d+)?)(?:\s+part\s+\d+)?\s*$"""), "")
        val names = (listOf(anime.title) + anime.alternativeTitles).filter { it.isNotBlank() }.distinct()
        val queries = names.flatMap { listOf(it, seriesName(it)) }.distinct().take(6)
        val expected = queries.map(::key).filter { it.isNotBlank() }.toSet()
        val type = if (anime.type == "MOVIE") MediaType.MOVIE else MediaType.TV
        val hits = linkedMapOf<Int, SearchResult>()
        fun matches(titles: List<String>) = titles.any { key(it) in expected }
        for (name in queries) {
            val page = get("search/${type.path}", mapOf("query" to name, "language" to "en-US", "include_adult" to "false"),
                SearchResponse.serializer())
            page.results.filter { 16 in it.genreIds && (it.originalLanguage == "ja" || "JP" in it.originCountry) }
                .forEach { hits[it.id] = it.copy(mediaType = type.path) }
            val exact = hits.values.filter { matches(listOfNotNull(it.title, it.name, it.originalTitle, it.originalName)) }
            if (exact.isNotEmpty()) return exact
        }
        // TMDB also indexes romanized names as aliases (e.g. Shingeki no Kyojin).
        val matched = mutableListOf<SearchResult>()
        for (hit in hits.values.take(6)) {
            val aliases = get("${type.path}/${hit.id}/alternative_titles", emptyMap(), AlternativeTitles.serializer())
            if (matches((aliases.results + aliases.titles).map { it.title })) matched += hit
        }
        return matched
    }

    /** Arabic details plus streaming availability, cast, trailers and recommendations. */
    suspend fun details(type: MediaType, id: Int): Details =
        get(
            "${type.path}/$id",
            mapOf(
                "language" to "ar",
                // Series: the cast of all seasons too ("credits" only has the latest one).
                "append_to_response" to "watch/providers,credits,videos,recommendations,external_ids" + if (type == MediaType.TV) ",aggregate_credits" else "",
                "include_video_language" to "ar,en",
            ),
            Details.serializer(),
        )

    /** A complete digital/parts order can split a broadcast season into multiple released parts. */
    suspend fun episodeParts(id: Int, seasons: List<SeasonInfo>): EpisodeParts? {
        val expected = seasons.sumOf { it.episodes }
        if (expected <= 0) return null
        val choices = get("tv/$id/episode_groups", emptyMap(), EpisodeGroupList.serializer()).results
            .filter { it.type == 4 && it.episodeCount == expected && it.groupCount > 0 &&
                (it.name.equals("Parts", true) || it.name.contains("Netflix", true)) &&
                it.id.matches(Regex("[a-fA-F0-9]{24}")) }
            .sortedBy { if (it.name.equals("Parts", true)) 0 else 1 }
        for (choice in choices.take(2)) {
            val details = get("tv/episode_group/${choice.id}", emptyMap(), EpisodeGroupDetails.serializer())
            val groups = details.groups.sortedBy { it.order }
            val episodes = groups.flatMap { it.episodes }
            // An incomplete or duplicated group must not invent missing episodes or replace the normal order.
            if (groups.size != choice.groupCount || groups.any { it.episodes.isEmpty() } ||
                episodes.size != expected || episodes.map { it.id }.distinct().size != expected ||
                episodes.any { it.id <= 0 || it.season <= 0 || it.episode <= 0 }) continue
            val parts = groups.mapIndexed { index, group -> EpisodePart(index + 1, group.episodes.size,
                group.episodes.mapNotNull { it.airDate?.take(4)?.toIntOrNull() }.minOrNull()) }
            if (parts.map { it.episodes } == seasons.sortedBy { it.number }.map { it.episodes }) continue
            return EpisodeParts(choice.name, parts)
        }
        return null
    }

    /** English details: search titles, a fuller story, reviews and English trailers. */
    suspend fun englishDetails(type: MediaType, id: Int): Details =
        get(
            "${type.path}/$id",
            mapOf("language" to "en-US", "append_to_response" to "reviews,videos"),
            Details.serializer(),
        )

    /**
     * TMDB discover. With [streamableOnly] the results are limited to titles that stream
     * in the user's region; it is always on when a provider filter is given.
     */
    suspend fun discover(
        type: MediaType,
        params: Map<String, String>,
        page: Int = 1,
        streamableOnly: Boolean = true,
    ): List<SearchResult> = discoverPage(type, params, page, streamableOnly).results

    suspend fun discoverPage(
        type: MediaType,
        params: Map<String, String>,
        page: Int = 1,
        streamableOnly: Boolean = true,
    ): SearchResponse {
        val base = mutableMapOf("language" to "ar", "include_adult" to "false", "page" to "$page")
        if (streamableOnly || "with_watch_providers" in params) {
            base["watch_region"] = settings.region.value
            base["with_watch_monetization_types"] = "flatrate|free|ads"
        }
        val response = get("discover/${type.path}", base + params, SearchResponse.serializer())
        return response.copy(results = response.results.map { it.copy(mediaType = type.path) })
    }

    suspend fun trending(type: MediaType): List<SearchResult> = trendingPage(type, 1).results

    suspend fun trendingPage(type: MediaType, page: Int): SearchResponse {
        val response = get("trending/${type.path}/week", mapOf("language" to "ar", "page" to "$page"), SearchResponse.serializer())
        return response.copy(results = response.results.map { it.copy(mediaType = type.path) })
    }

    /** Every streaming service TMDB (via JustWatch) knows in the user's region, most popular first. */
    suspend fun regionProviders(type: MediaType): List<ProviderDto> =
        get(
            "watch/providers/${type.path}",
            mapOf("language" to "ar", "watch_region" to settings.region.value),
            ProviderList.serializer(),
        ).results.sortedBy { it.displayPriority }

    /** An actor's details (Arabic where TMDB has it) with every film and series they appear in. */
    suspend fun person(id: Int): PersonDetails =
        get("person/$id", mapOf("language" to "ar", "append_to_response" to "combined_credits"), PersonDetails.serializer())

    /** English details, for the biography when there is no Arabic one. */
    suspend fun personEnglish(id: Int): PersonDetails =
        get("person/$id", mapOf("language" to "en-US"), PersonDetails.serializer())

    /** One page of any [RowSource]. TMDB serves at most 500 pages. */
    suspend fun page(source: RowSource, page: Int): SearchResponse = when (source) {
        is RowSource.Discover -> discoverPage(source.type, source.params, page, source.streamableOnly)
        is RowSource.Trending -> trendingPage(source.type, page)
    }

    /** A series' status in English ("Returning Series", "Ended", …), for the poster badge. */
    suspend fun tvStatus(id: Int): String? =
        get("tv/$id", mapOf("language" to "en-US"), Details.serializer()).status

    suspend fun recommendations(type: MediaType, id: Int): List<SearchResult> =
        get("${type.path}/$id/recommendations", mapOf("language" to "ar"), SearchResponse.serializer())
            .results.map { if (it.mediaType == null) it.copy(mediaType = type.path) else it }
            .filter { it.type != null }

    private suspend fun <T> get(
        path: String,
        params: Map<String, String>,
        deserializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val key = settings.apiKey.value.trim()
        if (key.isEmpty()) throw TmdbException("خدمة بيانات المحتوى غير متاحة في هذه النسخة؛ ثبّت آخر تحديث")
        val bearer = key.startsWith("eyJ")

        val url = "$BASE/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            if (!bearer) addQueryParameter("api_key", key)
        }.build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (bearer) header("Authorization", "Bearer $key") }
            .build()

        // One quiet retry for dropped connections (common on mobile data when many rows load at once).
        var lastIo: IOException? = null
        repeat(2) { attempt ->
            try {
                return@withContext client.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> throw TmdbException("تعذر الوصول إلى خدمة بيانات المحتوى حالياً؛ حاول لاحقاً")
                        response.code == 404 -> throw TmdbException("ما لقينا هذا العمل")
                        !response.isSuccessful -> throw TmdbException("صار خطأ من TMDB (${response.code})")
                    }
                    val body = response.body?.string() ?: throw TmdbException("رد فاضي من TMDB")
                    json.decodeFromString(deserializer, body)
                }
            } catch (e: IOException) {
                lastIo = e
                if (attempt == 0) kotlinx.coroutines.delay(700)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw TmdbException("ما قدرنا نقرأ رد TMDB")
            }
        }
        // Include the technical reason so a screenshot tells what went wrong.
        throw TmdbException("تأكد من اتصالك بالإنترنت (TMDB: ${lastIo?.javaClass?.simpleName ?: "IO"})")
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"

        /** TMDB image URL for [path]; full URLs (e.g. MyAnimeList posters) pass through. */
        fun image(path: String?, size: String = "w342"): String? = when {
            path == null -> null
            path.startsWith("http") -> path
            else -> "https://image.tmdb.org/t/p/$size$path"
        }
    }
}
