package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
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

/** The user's own Xtream Codes login from their provider. */
data class XtreamAccount(val host: String, val username: String, val password: String) {
    val isComplete: Boolean get() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
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
    LIVE("القنوات", "get_live_categories", "get_live_streams", "live"),
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
)

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
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val account: XtreamAccount get() = settings.xtream.value

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

    suspend fun categories(kind: XtreamKind): List<XtreamCategory> =
        (call(account, kind.categoriesAction) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { c ->
                val id = c.text("category_id") ?: return@mapNotNull null
                XtreamCategory(id, c.text("category_name") ?: id)
            }
            .distinctBy { it.id }

    suspend fun items(kind: XtreamKind, categoryId: String?): List<XtreamItem> {
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
                )
            }
            .distinctBy { it.id }
    }

    suspend fun series(seriesId: String): XtreamSeries {
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
    fun playUrls(item: XtreamItem): List<String> = when (item.kind) {
        XtreamKind.LIVE -> listOf(streamUrl("live", "${item.id}.m3u8"), streamUrl("live", "${item.id}.ts"))
        XtreamKind.MOVIE -> listOf(streamUrl("movie", "${item.id}.${item.extension ?: "mp4"}"))
        XtreamKind.SERIES -> emptyList()
    }

    fun playUrls(episode: XtreamEpisode): List<String> =
        listOf(streamUrl("series", "${episode.id}.${episode.extension ?: "mp4"}"))

    private fun streamUrl(type: String, file: String): String {
        val a = account
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
    }
}
