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

class TmdbException(message: String) : Exception(message)

@Serializable
data class SearchResponse(val results: List<SearchResult> = emptyList())

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
) {
    val type: MediaType? get() = MediaType.fromPath(mediaType)
    val displayTitle: String get() = title ?: name ?: ""
    val year: String? get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }
}

@Serializable
data class Genre(val name: String = "")

@Serializable
data class SeasonDto(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
)

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
)

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
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val genres: List<Genre> = emptyList(),
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

    suspend fun search(query: String): List<SearchResult> =
        get("search/multi", mapOf("query" to query, "language" to "ar", "include_adult" to "false"), SearchResponse.serializer())
            .results
            .filter { it.type != null }

    /** Arabic details plus streaming availability for every region. */
    suspend fun details(type: MediaType, id: Int): Details =
        get("${type.path}/$id", mapOf("language" to "ar", "append_to_response" to "watch/providers"), Details.serializer())

    /** English details, used for titles to search with and as an overview fallback. */
    suspend fun englishDetails(type: MediaType, id: Int): Details =
        get("${type.path}/$id", mapOf("language" to "en-US"), Details.serializer())

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

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> throw TmdbException("مفتاح TMDB غير صحيح، راجعه في الإعدادات")
                    response.code == 404 -> throw TmdbException("ما لقينا هذا العمل")
                    !response.isSuccessful -> throw TmdbException("صار خطأ من TMDB (${response.code})")
                }
                val body = response.body?.string() ?: throw TmdbException("رد فاضي من TMDB")
                json.decodeFromString(deserializer, body)
            }
        } catch (e: IOException) {
            throw TmdbException("تأكد من اتصالك بالإنترنت")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw TmdbException("ما قدرنا نقرأ رد TMDB")
        }
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"

        fun image(path: String?, size: String = "w342"): String? =
            path?.let { "https://image.tmdb.org/t/p/$size$it" }
    }
}
