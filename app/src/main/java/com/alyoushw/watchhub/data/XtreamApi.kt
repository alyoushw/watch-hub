package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One of the user's Xtream Codes logins from their providers, with the name they gave it. */
@kotlinx.serialization.Serializable
data class XtreamAccount(
    val host: String,
    val username: String,
    val password: String,
    val id: String = "",
    val name: String = "",
) {
    val isComplete: Boolean get() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** What to call it on buttons: the user's name for it, else the server's host. */
    val label: String get() = name.ifBlank { host.substringAfter("://").substringBefore("/") }

    companion object {
        /**
         * The same id for the same login on every device (phone, TV, web), so synced history
         * points at the right account everywhere: FNV-1a of "host|username", base 36.
         * The web app computes it the same way (store.js stableAccountId).
         */
        fun stableId(host: String, username: String): String {
            val text = XtreamApi.normalizeHost(host).lowercase() + "|" + username.trim()
            var h = 0x811C9DC5L
            for (c in text) {
                h = h xor c.code.toLong()
                h = (h * 0x01000193L) and 0xFFFFFFFFL
            }
            return "x" + h.toString(36)
        }
    }
}

/** What the provider says about the account (from player_api.php's user_info). */
data class XtreamAccountInfo(
    val status: String?,
    val expiresAt: Date?,
    val maxConnections: String?,
    val activeConnections: String?,
)

/** The three kinds of content an Xtream Codes account has. */
enum class XtreamKind(val label: String, val categoriesAction: String, val listAction: String, val pathSegment: String) {
    LIVE("مباشر", "get_live_categories", "get_live_streams", "live"),
    MOVIE("الأفلام", "get_vod_categories", "get_vod_streams", "movie"),
    SERIES("المسلسلات", "get_series_categories", "get_series", "series"),
}

data class XtreamCategory(val id: String, val name: String)

/** A channel, a film or a series in the account. */
data class XtreamItem(
    val kind: XtreamKind,
    val id: String,
    val name: String,
    val image: String?,
    val extension: String?,
    val rating: String?,
    val categoryId: String? = null,
    /** Channel number from the provider. */
    val num: Int? = null,
    /** TMDB id when the provider has it, used to match a title page to the account. */
    val tmdb: String? = null,
    val year: String? = null,
)

/** One programme from the channel guide. */
data class XtreamProgram(val title: String, val start: Date?, val end: Date?)

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String?,
    val image: String?,
)

data class XtreamSeries(
    val name: String,
    val plot: String?,
    val cover: String?,
    val episodes: List<XtreamEpisode>,
)

class XtreamException(message: String) : ApiException(message)

/** Minimal client for the Xtream Codes player API: checks the login and lists the account's content. */
class XtreamApi(private val settings: SettingsStore) {
    // Shares Net's pool, which is dropped when the phone changes network.
    private val client = Net.streams.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** The account shown on the channels / films / series screens. */
    private val active: XtreamAccount get() = settings.xtream.value

    /** Logs in with [account] and returns the account details, or throws [XtreamException]. */
    suspend fun check(account: XtreamAccount): XtreamAccountInfo {
        val root = call(account, action = null) as? JsonObject ?: throw notXtream()
        val user = root["user_info"] as? JsonObject ?: throw notXtream()
        if (user.text("auth") != "1") throw XtreamException("اسم المستخدم أو كلمة المرور غلط")
        return XtreamAccountInfo(
            status = user.text("status"),
            expiresAt = user.text("exp_date")?.toLongOrNull()?.let { Date(it * 1000) },
            maxConnections = user.text("max_connections"),
            activeConnections = user.text("active_cons"),
        )
    }

    suspend fun categories(kind: XtreamKind, account: XtreamAccount = active): List<XtreamCategory> =
        (call(account, kind.categoriesAction) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { c ->
                val id = c.text("category_id") ?: return@mapNotNull null
                XtreamCategory(id, c.text("category_name") ?: id)
            }
            .distinctBy { it.id }

    suspend fun items(kind: XtreamKind, categoryId: String?, account: XtreamAccount = active): List<XtreamItem> {
        val params = categoryId?.let { mapOf("category_id" to it) }.orEmpty()
        return (call(account, kind.listAction, params) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val id = (if (kind == XtreamKind.SERIES) o.text("series_id") else o.text("stream_id"))
                    ?: return@mapNotNull null
                XtreamItem(
                    kind = kind,
                    id = id,
                    name = o.text("name") ?: o.text("title") ?: id,
                    image = (if (kind == XtreamKind.SERIES) o.text("cover") else o.text("stream_icon"))?.takeIf { it.startsWith("http") },
                    extension = o.text("container_extension"),
                    rating = o.text("rating")?.takeIf { it != "0" && it != "0.0" },
                    categoryId = o.text("category_id"),
                    num = o.text("num")?.toIntOrNull(),
                    tmdb = (o.text("tmdb") ?: o.text("tmdb_id"))?.takeIf { it != "0" },
                    year = o.text("year")?.take(4)
                        ?: (o.text("releaseDate") ?: o.text("release_date"))?.take(4)?.takeIf { it.length == 4 },
                )
            }
            .distinctBy { it.id }
    }

    private val cache = mutableMapOf<String, List<XtreamItem>>()
    private val cacheLock = Mutex()

    /** Every item of [kind] in the account, loaded once per login and kept in memory. */
    suspend fun all(kind: XtreamKind, account: XtreamAccount = active): List<XtreamItem> {
        val key = "${account.host}|${account.username}|$kind"
        cacheLock.withLock {
            cache[key]?.let { return it }
            val list = items(kind, null, account)
            cache[key] = list
            return list
        }
    }

    /** Now and next on a channel (titles come base64-encoded). */
    suspend fun shortEpg(streamId: String, account: XtreamAccount = active): List<XtreamProgram> {
        val root = call(account, "get_short_epg", mapOf("stream_id" to streamId, "limit" to "2")) as? JsonObject
            ?: return emptyList()
        return (root["epg_listings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { p ->
            val raw = p.text("title") ?: return@mapNotNull null
            XtreamProgram(
                title = decodeBase64(raw),
                start = p.text("start_timestamp")?.toLongOrNull()?.let { Date(it * 1000) },
                end = (p.text("stop_timestamp") ?: p.text("end_timestamp"))?.toLongOrNull()?.let { Date(it * 1000) },
            )
        }
    }

    /**
     * Finds a film or series from a title page in the account: by TMDB id when the provider
     * lists it, otherwise by name (Arabic or English), preferring the same year.
     */
    suspend fun findMatch(
        kind: XtreamKind,
        tmdbId: Int,
        titles: List<String>,
        year: String?,
        account: XtreamAccount = active,
    ): XtreamItem? {
        val list = all(kind, account)
        list.firstOrNull { it.tmdb == tmdbId.toString() }?.let { return it }
        val wanted = titles.map { normalizeTitle(it) }.filter { it.length >= 2 }.toSet()
        if (wanted.isEmpty()) return null
        val hits = list.filter { normalizeTitle(it.name) in wanted }
        return hits.firstOrNull { year != null && (it.year == year || it.name.contains(year)) } ?: hits.firstOrNull()
    }

    suspend fun series(seriesId: String, account: XtreamAccount = active): XtreamSeries {
        val root = call(account, "get_series_info", mapOf("series_id" to seriesId)) as? JsonObject ?: throw notXtream()
        val info = root["info"] as? JsonObject
        // Episodes come as {"1": [...], "2": [...]} on most servers, a plain list on some.
        val groups: List<JsonElement> = when (val e = root["episodes"]) {
            is JsonObject -> e.values.flatMap { (it as? JsonArray).orEmpty() }
            is JsonArray -> e.flatMap { (it as? JsonArray) ?: listOf(it) }
            else -> emptyList()
        }
        val episodes = groups.mapNotNull { it as? JsonObject }.mapNotNull { ep ->
            val id = ep.text("id") ?: return@mapNotNull null
            val number = ep.text("episode_num")?.toIntOrNull() ?: 0
            XtreamEpisode(
                id = id,
                season = ep.text("season")?.toIntOrNull() ?: 1,
                number = number,
                title = ep.text("title") ?: "الحلقة $number",
                extension = ep.text("container_extension"),
                image = (ep["info"] as? JsonObject)?.text("movie_image")?.takeIf { it.startsWith("http") },
            )
        }.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number }))
        return XtreamSeries(
            name = info?.text("name").orEmpty(),
            plot = info?.text("plot"),
            cover = info?.text("cover")?.takeIf { it.startsWith("http") },
            episodes = episodes,
        )
    }

    /** Links to try in order for a channel or a film (live: HLS first, then MPEG-TS). */
    fun playUrls(item: XtreamItem, account: XtreamAccount = active): List<String> = when (item.kind) {
        XtreamKind.LIVE -> listOf(streamUrl(account, "live", "${item.id}.m3u8"), streamUrl(account, "live", "${item.id}.ts"))
        XtreamKind.MOVIE -> listOf(streamUrl(account, "movie", "${item.id}.${item.extension ?: "mp4"}"))
        XtreamKind.SERIES -> emptyList()
    }

    /** Links for something from the watch history, from the account it was watched on. */
    fun playUrls(entry: WatchEntry): List<String> {
        val a = settings.xtreamAccount(entry.accountId) ?: active
        return when (entry.kind) {
            WatchEntry.LIVE -> listOf(streamUrl(a, "live", "${entry.itemId}.m3u8"), streamUrl(a, "live", "${entry.itemId}.ts"))
            WatchEntry.MOVIE -> listOf(streamUrl(a, "movie", "${entry.itemId}.${entry.ext ?: "mp4"}"))
            else -> listOf(streamUrl(a, "series", "${entry.itemId}.${entry.ext ?: "mp4"}"))
        }
    }

    fun playUrls(episode: XtreamEpisode, account: XtreamAccount = active): List<String> =
        listOf(streamUrl(account, "series", "${episode.id}.${episode.extension ?: "mp4"}"))

    private fun streamUrl(a: XtreamAccount, type: String, file: String): String {
        return baseUrl(a).newBuilder()
            .addPathSegment(type)
            .addPathSegment(a.username)
            .addPathSegment(a.password)
            .addPathSegment(file)
            .build()
            .toString()
    }

    private fun baseUrl(account: XtreamAccount): HttpUrl =
        normalizeHost(account.host).toHttpUrlOrNull()
            ?: throw XtreamException("رابط الهوست غير صحيح، مثال: http://example.com:8080")

    private suspend fun call(account: XtreamAccount, action: String?, params: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            if (!account.isComplete) throw XtreamException("أضف حساب Xtream Codes من الإعدادات أول")
            val url = baseUrl(account).newBuilder()
                .addPathSegment("player_api.php")
                .addQueryParameter("username", account.username)
                .addQueryParameter("password", account.password)
                .apply {
                    action?.let { addQueryParameter("action", it) }
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                }
                .build()

            val body = try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.code == 401 || response.code == 403) throw XtreamException("اسم المستخدم أو كلمة المرور غلط")
                    if (!response.isSuccessful) throw XtreamException("السيرفر رد بخطأ (${response.code})")
                    response.body?.string().orEmpty()
                }
            } catch (e: XtreamException) {
                throw e
            } catch (e: IOException) {
                throw XtreamException("ما قدرنا نوصل للسيرفر، تأكد من الهوست والإنترنت")
            }
            runCatching { json.parseToJsonElement(body) }.getOrNull() ?: throw notXtream()
        }

    private fun notXtream() = XtreamException("الرد مو من سيرفر Xtream Codes، تأكد من الهوست")

    private fun JsonObject.text(key: String): String? {
        val value: JsonElement = this[key] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** "example.com:8080/" → "http://example.com:8080", and drops a pasted "player_api.php". */
        fun normalizeHost(raw: String): String {
            var host = raw.trim().trimEnd('/')
            if (host.endsWith("player_api.php")) host = host.removeSuffix("player_api.php").trimEnd('/')
            if (!host.startsWith("http://", ignoreCase = true) && !host.startsWith("https://", ignoreCase = true)) {
                host = "http://$host"
            }
            return host
        }

        fun formatDate(date: Date): String = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(date)

        fun formatTime(date: Date): String = SimpleDateFormat("HH:mm", Locale.US).format(date)

        private val tagPrefix = Regex("""^\s*(\[[^\]]*\]|\|[^|]{1,6}\||[A-Z]{2,3}\s*[|:]\s*|[A-Z]{2,3}\s+-\s+)""")
        private val bracketed = Regex("""[(\[{][^)\]}]*[)\]}]""")
        private val qualityWords = Regex("""\b(4k|uhd|fhd|hd|sd|hevc|multi[- ]?sub|vip)\b""")
        private val yearWord = Regex("""\b(19|20)\d{2}\b""")
        private val nonWord = Regex("""[^\p{L}\p{Nd}]+""")

        /** "AR| The Batman (2022) 4K" → "the batman"; also folds Arabic letter variants. */
        fun normalizeTitle(raw: String): String {
            var t = raw
            repeat(2) { t = t.replace(tagPrefix, "") }
            t = t.lowercase(Locale.ROOT)
                .replace(bracketed, " ")
                .replace(qualityWords, " ")
                .replace(yearWord, " ")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ة', 'ه').replace('ى', 'ي')
                .replace(nonWord, " ")
                .trim()
            return t.replace(Regex("""\s+"""), " ")
        }

        private fun decodeBase64(value: String): String =
            runCatching { String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: value
    }
}
