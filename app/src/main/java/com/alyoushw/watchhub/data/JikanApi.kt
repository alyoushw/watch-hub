package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class JikanException(message: String) : ApiException(message)

@Serializable
data class JImage(
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("large_image_url") val largeImageUrl: String? = null,
)

@Serializable
data class JImages(val jpg: JImage? = null, val webp: JImage? = null) {
    val best: String? get() = jpg?.largeImageUrl ?: webp?.largeImageUrl ?: jpg?.imageUrl ?: webp?.imageUrl
}

@Serializable
data class JNamed(val name: String = "", val url: String? = null)

@Serializable
data class JTrailer(@SerialName("youtube_id") val youtubeId: String? = null)

@Serializable
data class JAired(val string: String? = null)

@Serializable
data class JAnime(
    @SerialName("mal_id") val malId: Int = 0,
    val url: String? = null,
    val images: JImages? = null,
    val trailer: JTrailer? = null,
    val title: String = "",
    @SerialName("title_english") val titleEnglish: String? = null,
    @SerialName("title_japanese") val titleJapanese: String? = null,
    val type: String? = null,
    val source: String? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val aired: JAired? = null,
    val duration: String? = null,
    val rating: String? = null,
    val score: Double? = null,
    @SerialName("scored_by") val scoredBy: Int? = null,
    val rank: Int? = null,
    val popularity: Int? = null,
    val members: Int? = null,
    val synopsis: String? = null,
    val background: String? = null,
    val season: String? = null,
    val year: Int? = null,
    val studios: List<JNamed> = emptyList(),
    val genres: List<JNamed> = emptyList(),
    val themes: List<JNamed> = emptyList(),
    val demographics: List<JNamed> = emptyList(),
    /** Official streaming pages, e.g. a Crunchyroll or Netflix URL. Only on /full. */
    val streaming: List<JNamed> = emptyList(),
) {
    val displayTitle: String get() = titleEnglish?.ifBlank { null } ?: title

    /** Known services → the title's own page there. */
    fun directLinks(): Map<StreamingService, String> = buildMap {
        streaming.forEach { link ->
            val url = link.url ?: return@forEach
            val service = StreamingService.forUrl(url) ?: return@forEach
            if (service !in this) put(service, url)
        }
    }

    fun toSearchResult() = SearchResult(
        id = malId,
        mediaType = MediaType.ANIME.path,
        title = displayTitle,
        posterPath = images?.best,
        voteAverage = score,
        firstAirDate = year?.toString(),
    )

    fun toWatchItem(existing: WatchItem?): WatchItem {
        val base = existing ?: WatchItem(id = malId, type = MediaType.ANIME, title = displayTitle)
        val links = directLinks()
        return base.copy(
            title = displayTitle,
            searchTitle = titleEnglish?.ifBlank { null } ?: title,
            posterPath = images?.best ?: base.posterPath,
            providerIds = links.keys.map { it.tmdbIds.first() }.ifEmpty { base.providerIds },
            seasons = episodes?.takeIf { it > 0 }?.let { listOf(SeasonInfo(1, it)) } ?: base.seasons,
            directLinks = links.mapKeys { it.key.key }.ifEmpty { base.directLinks },
        )
    }
}

@Serializable
data class JList(val data: List<JAnime> = emptyList())

@Serializable
data class JSingle(val data: JAnime)

@Serializable
data class JRecommendation(val entry: JAnime)

@Serializable
data class JRecommendations(val data: List<JRecommendation> = emptyList())

@Serializable
data class JCharacter(val name: String = "", val images: JImages? = null)

@Serializable
data class JCharacterRole(val character: JCharacter = JCharacter(), val role: String = "")

@Serializable
data class JCharacters(val data: List<JCharacterRole> = emptyList())

@Serializable
data class JUser(val username: String = "")

@Serializable
data class JReview(
    val user: JUser? = null,
    val review: String = "",
    val score: Int? = null,
    val date: String? = null,
)

@Serializable
data class JReviews(val data: List<JReview> = emptyList())

/**
 * MyAnimeList data through Jikan (api.jikan.moe), a free, key-less MAL API.
 * Jikan allows about 3 requests a second, so calls are spaced out and retried on 429.
 */
class JikanApi {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val gate = Mutex()
    private var lastCall = 0L

    suspend fun top(filter: String? = null): List<JAnime> =
        get("top/anime", buildMap {
            put("limit", "20")
            put("sfw", "true")
            if (filter != null) put("filter", filter)
        }, JList.serializer()).data.distinctBy { it.malId }

    suspend fun seasonNow(): List<JAnime> =
        get("seasons/now", mapOf("limit" to "20", "sfw" to "true"), JList.serializer()).data.distinctBy { it.malId }

    suspend fun anime(id: Int): JAnime = get("anime/$id/full", emptyMap(), JSingle.serializer()).data

    suspend fun characters(id: Int): List<JCharacterRole> =
        get("anime/$id/characters", emptyMap(), JCharacters.serializer()).data

    suspend fun reviews(id: Int): List<JReview> =
        get("anime/$id/reviews", mapOf("preliminary" to "true", "spoilers" to "false"), JReviews.serializer()).data

    suspend fun recommendations(id: Int): List<JAnime> =
        get("anime/$id/recommendations", emptyMap(), JRecommendations.serializer()).data.map { it.entry }

    private suspend fun <T : Any> get(
        path: String,
        params: Map<String, String>,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val url = "$BASE/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "WatchHub/0.6 (Android)")
            .build()

        repeat(3) { attempt ->
            gate.withLock {
                val wait = lastCall + MIN_GAP_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastCall = System.currentTimeMillis()
            }
            val result: T? = withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).execute().use { response ->
                        when {
                            response.code == 429 -> null
                            response.code == 404 -> throw JikanException("ما لقينا هذا الأنمي")
                            !response.isSuccessful -> throw JikanException("MyAnimeList مو متاح الحين (${response.code})")
                            else -> json.decodeFromString(deserializer, response.body?.string().orEmpty())
                        }
                    }
                } catch (e: IOException) {
                    throw JikanException("ما قدرنا نوصل لـ MyAnimeList (${e.javaClass.simpleName})")
                } catch (e: SerializationException) {
                    throw JikanException("ما قدرنا نقرأ بيانات MyAnimeList")
                } catch (e: JikanException) {
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw JikanException("MyAnimeList ما رد: ${e.javaClass.simpleName}")
                }
            }
            if (result != null) return result
            delay(1200L * (attempt + 1))
        }
        throw JikanException("MyAnimeList مشغول، حاول بعد شوي")
    }

    private companion object {
        const val BASE = "https://api.jikan.moe/v4"
        const val MIN_GAP_MS = 400L
    }
}
