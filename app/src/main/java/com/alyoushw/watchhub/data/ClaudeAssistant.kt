package com.alyoushw.watchhub.data

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate

/** Result of asking Claude, or why the app fell back to the offline parser. */
sealed interface AssistantResult {
    data class Understood(val query: SmartQuery, val explanation: String) : AssistantResult

    /** Claude could not be used; [reason] is shown to the user, the offline parser takes over. */
    data class Unavailable(val reason: String) : AssistantResult
}

@Serializable
private data class ClaudeIntent(
    val intent: String,
    @SerialName("media_type") val mediaType: String,
    val genres: List<String>,
    val origin: String,
    val services: List<String>,
    val sort: String,
    @SerialName("year_from") val yearFrom: Int,
    @SerialName("year_to") val yearTo: Int,
    @SerialName("min_rating") val minRating: Int,
    val title: String,
    val explanation: String,
)

/**
 * Turns a spoken or typed request ("ابي مسلسل كوري درامي") into a search filter with Claude.
 * Uses the user's own Claude API key; on any failure (no key, limit reached, no credit,
 * network) the caller falls back to the offline [SmartSearch] parser.
 */
class ClaudeAssistant(private val settings: SettingsStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    /** After a rate-limit or billing error, skip Claude for a while instead of failing every search. */
    @Volatile
    private var pausedUntil = 0L

    val isEnabled: Boolean get() = settings.useClaude.value && settings.claudeKey.value.isNotBlank()

    private fun client(key: String): AnthropicClient {
        val existing = client
        if (existing != null && clientKey == key) return existing
        return AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(25))
            .maxRetries(1)
            .build()
            .also {
                client = it
                clientKey = key
            }
    }

    suspend fun interpret(request: String): AssistantResult {
        val key = settings.claudeKey.value.trim()
        if (key.isEmpty()) return AssistantResult.Unavailable("ما فيه مفتاح Claude")
        if (System.currentTimeMillis() < pausedUntil) {
            return AssistantResult.Unavailable("حد Claude خلص مؤقتاً، استخدمنا البحث العادي")
        }
        return withContext(Dispatchers.IO) {
            try {
                val params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(2048L)
                    .system(systemPrompt())
                    .outputConfig(
                        OutputConfig.builder()
                            .effort(OutputConfig.Effort.LOW)
                            .format(JsonOutputFormat.builder().schema(schema()).build())
                            .build(),
                    )
                    .addUserMessage(request)
                    .build()
                val message = client(key).messages().create(params)
                if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
                    return@withContext AssistantResult.Unavailable("Claude ما قدر يفهم الطلب")
                }
                val text = message.content().firstNotNullOfOrNull { it.text().orElse(null)?.text() }
                    ?: return@withContext AssistantResult.Unavailable("رد فاضي من Claude")
                val parsed = json.decodeFromString(ClaudeIntent.serializer(), text)
                AssistantResult.Understood(parsed.toQuery(request), parsed.explanation)
            } catch (e: RateLimitException) {
                pause(minutes = 15)
                AssistantResult.Unavailable("وصلت حد Claude، استخدمنا البحث العادي")
            } catch (e: UnauthorizedException) {
                AssistantResult.Unavailable("مفتاح Claude غير صحيح، راجعه في الإعدادات")
            } catch (e: PermissionDeniedException) {
                AssistantResult.Unavailable("مفتاح Claude ما عنده صلاحية")
            } catch (e: AnthropicServiceException) {
                // 402 = no credit left; 529 = overloaded.
                when (e.statusCode()) {
                    402 -> {
                        pause(minutes = 60)
                        AssistantResult.Unavailable("رصيد Claude خلص، استخدمنا البحث العادي")
                    }
                    529 -> AssistantResult.Unavailable("Claude مشغول، استخدمنا البحث العادي")
                    else -> AssistantResult.Unavailable("Claude ما رد (${e.statusCode()})، استخدمنا البحث العادي")
                }
            } catch (e: AnthropicIoException) {
                AssistantResult.Unavailable("ما قدرنا نوصل لـ Claude")
            } catch (e: kotlinx.serialization.SerializationException) {
                AssistantResult.Unavailable("ما فهمنا رد Claude")
            }
        }
    }

    private fun pause(minutes: Long) {
        pausedUntil = System.currentTimeMillis() + minutes * 60_000
    }

    private fun ClaudeIntent.toQuery(original: String): SmartQuery {
        val type = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE
        return when (intent) {
            "similar" -> if (title.isNotBlank()) {
                SmartQuery.Similar(title, type, Origin.entries.firstOrNull { it.name == origin })
            } else {
                SmartQuery.Plain(original)
            }
            "watchlist" -> SmartQuery.FromWatchlist
            "title" -> SmartQuery.Plain(title.ifBlank { original })
            else -> SmartQuery.Filter(
                DiscoverFilter(
                    type = type,
                    genres = genres.mapNotNull { name -> Genre.entries.firstOrNull { it.name == name } }.toSet(),
                    origin = Origin.entries.firstOrNull { it.name == origin },
                    services = services.filter { key -> StreamingService.forKey(key) != null }.toSet(),
                    sort = SortMode.entries.firstOrNull { it.name == sort } ?: SortMode.POPULAR,
                    yearFrom = yearFrom.takeIf { it in 1900..2100 },
                    yearTo = yearTo.takeIf { it in 1900..2100 },
                    minRating = minRating.takeIf { it in 1..9 },
                ),
            )
        }
    }

    private fun systemPrompt(): String = """
        You turn a viewer's request (usually Gulf Arabic, sometimes English, often spoken) into a search for a
        movie and TV discovery app. Today is ${LocalDate.now()}. The user's region is ${settings.region.value}.

        Pick the intent:
        - "similar": they want titles like a specific movie/show they name. Put that title in `title`,
          in its best-known English form if you know it (e.g. "انترستلر" -> "Interstellar").
        - "watchlist": they want recommendations based on their own saved list ("من قائمتي", "على ذوقي").
        - "title": they are looking for one specific title by name. Put the name in `title` (English if known).
        - "filter": anything else - a mood, genre, country, era, platform or quality bar.

        For "filter", map the request to the closest options. Moods count: "يضحك" is COMEDY, "يخوف" is HORROR,
        "يبكي" is DRAMA, "خفيف" is COMEDY or FAMILY. Anime means media_type "tv" (or "movie" if they say film),
        genre ANIMATION and origin JAPANESE. If they don't say movie or series, choose what fits best
        (Korean/Turkish drama -> tv). Use sort TOP_RATED for "best/أفضل", RECENT for "new/جديد".
        Use 0 for year_from, year_to and min_rating when not given, "NONE" for origin, [] for empty lists,
        and "" for title when there is none.

        `explanation` is one short Arabic sentence (Gulf dialect is fine) saying what you understood,
        e.g. "مسلسلات دراما كورية، الأعلى تقييماً".
    """.trimIndent()

    private fun schema(): JsonOutputFormat.Schema {
        fun enumOf(values: List<String>) = mapOf("type" to "string", "enum" to values)
        val properties = mapOf(
            "intent" to enumOf(listOf("filter", "similar", "watchlist", "title")),
            "media_type" to enumOf(listOf("movie", "tv")),
            "genres" to mapOf("type" to "array", "items" to enumOf(Genre.entries.map { it.name })),
            "origin" to enumOf(Origin.entries.map { it.name } + "NONE"),
            "services" to mapOf("type" to "array", "items" to enumOf(StreamingService.entries.map { it.key })),
            "sort" to enumOf(SortMode.entries.map { it.name }),
            "year_from" to mapOf("type" to "integer"),
            "year_to" to mapOf("type" to "integer"),
            "min_rating" to mapOf("type" to "integer"),
            "title" to mapOf("type" to "string"),
            "explanation" to mapOf("type" to "string"),
        )
        return JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(properties))
            .putAdditionalProperty("required", JsonValue.from(properties.keys.toList()))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }

    /** Quick check used by the "test" button in Settings. */
    suspend fun test(): String = when (val r = interpret("مسلسل كوري درامي")) {
        is AssistantResult.Understood -> "✓ شغّال: ${r.explanation}"
        is AssistantResult.Unavailable -> r.reason
    }

    private companion object {
        const val MODEL = "claude-opus-5-5"
    }
}
