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

    suspend fun searchPage(query: String, page: Int = 1): SearchResponse {
        val response = get(
            "search/multi",
            mapOf("query" to query, "language" to "ar", "include_adult" to "false", "page" to "${page.coerceIn(1, 500)}"),
            SearchResponse.serializer(),
        )
        return response.copy(results = response.results.filter { it.type != null })
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
        if (key.isEmpty()) throw TmdbException("أضف مفتاح TMDB من الإعدادات أول")
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
                        response.code == 401 -> throw TmdbException("مفتاح TMDB غير صحيح، راجعه في الإعدادات")
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
