package com.alyoushw.watchhub.data

import com.alyoushw.watchhub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class AnimeifyException(message: String) : ApiException(message)

/** Stable identifiers only. Resolved CDN URLs are deliberately never persisted. */
@Serializable
data class AnimeEpisodeRef(
    val animeId: String,
    val malId: Int,
    val type: String,
    val season: Int,
    val episode: String,
    val server: String,
    val detailType: String = "anime",
    val detailId: Int = malId,
)

data class AnimeifyTitle(
    val id: String,
    val malId: Int?,
    val title: String,
    val type: String,
    val season: Int,
    val relation: String,
)

data class AnimeifyEpisode(val number: String, val extra: String = "")
data class AnimeifyServer(val key: String, val label: String, val value: String)

/** The owner's Animeify v3.2 API. Only catalogue/episode reads are used; no user account is needed. */
class AnimeifyApi(
    private val token: String = BuildConfig.ANIMEIFY_TOKEN,
    private val base: String = "https://animeify.net/animeify/apis_v4/",
    private val http: OkHttpClient = Net.streams.newBuilder().callTimeout(30, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(title: String, movie: Boolean): List<AnimeifyTitle> = titles(post(
        "anime/load_anime_list_v2.php", mapOf(
            "UserId" to "0", "Language" to "ar", "FilterType" to "SEARCH", "FilterData" to title,
            "Type" to if (movie) "MOVIE" else "SERIES", "From" to "0",
        ),
    ))

    suspend fun related(title: AnimeifyTitle): List<AnimeifyTitle> {
        if (title.relation.isBlank()) return listOf(title)
        return (listOf(title) + titles(post("anime/load_related_anime.php", mapOf(
            "UserId" to "0", "Language" to "ar", "RelationId" to title.relation, "RelatedId" to title.id,
        )))).distinctBy { it.id }.sortedWith(compareBy({ it.type != "SERIES" }, { it.season }, { it.title }))
    }

    suspend fun episodes(id: String): List<AnimeifyEpisode> = parseEpisodes(post(
        "episodes/load_episodes.php", mapOf("AnimeID" to id),
    ))

    suspend fun servers(ref: AnimeEpisodeRef): List<AnimeifyServer> {
        val result = post("anime/load_servers.php", mapOf(
            "UserId" to "0", "AnimeId" to ref.animeId, "Episode" to ref.episode, "AnimeType" to ref.type,
        )) as? JsonObject ?: throw AnimeifyException("تعذّر قراءة سيرفرات الحلقة")
        return parseServers(result["CurrentEpisode"] as? JsonObject ?: return emptyList())
    }

    /** Refresh the server entry each time, including retries and resume after leaving the app. */
    suspend fun resolve(ref: AnimeEpisodeRef): String {
        val server = servers(ref).firstOrNull { it.key == ref.server }
            ?: throw AnimeifyException("السيرفر المختار غير متاح لهذه الحلقة؛ اختر سيرفراً آخر من صفحة الأنمي")
        val value = server.value
        val url = when {
            value.toHttpUrlOrNull()?.encodedPath?.endsWith(".mp4", true) == true -> value
            server.key.startsWith("FR") -> {
                val page = fetch("https://www.mediafire.com/file/${safeId(value)}")
                mediaFireUrl(page) ?: throw AnimeifyException("تعذّر جلب رابط MediaFire؛ اختر سيرفراً آخر")
            }
            server.key.startsWith("SV") -> {
                val doc = Jsoup.parse(fetch("https://sendvid.com/embed/${safeId(value)}"))
                doc.selectFirst("source[src], video[src]")?.attr("src")
                    ?: throw AnimeifyException("تعذّر جلب رابط Sendvid؛ اختر سيرفراً آخر")
            }
            server.key == "OKLink" -> okVideoUrl(fetch("https://ok.ru/videoembed/${safeId(value)}"))
                ?: throw AnimeifyException("تعذّر جلب رابط OK؛ اختر سيرفراً آخر")
            else -> throw AnimeifyException("صيغة السيرفر غير مدعومة")
        }
        return url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString()
            ?: throw AnimeifyException("لم يُرجع السيرفر رابط تشغيل HTTPS صالحاً")
    }

    private fun safeId(value: String): String {
        if (!value.matches(Regex("[A-Za-z0-9_-]{3,160}"))) throw AnimeifyException("معرّف السيرفر غير صالح")
        return value
    }

    private suspend fun post(path: String, fields: Map<String, String>): JsonElement {
        if (token.isBlank()) throw AnimeifyException("مصدر Animeify غير مهيأ في هذه النسخة")
        val form = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
            add("Token", token)
        }.build()
        val text = request(Request.Builder().url(base + path).post(form).build())
        return try { json.parseToJsonElement(text.trimStart('\uFEFF', ' ', '\r', '\n')) }
        catch (_: IllegalArgumentException) { throw AnimeifyException("ردّ مصدر Animeify ببيانات غير صالحة") }
    }

    private suspend fun fetch(url: String): String = request(Request.Builder().url(url)
        .header("User-Agent", "Mozilla/5.0 (Android) WatchHub").build())

    private suspend fun request(request: Request): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw AnimeifyException("مصدر الأنمي غير متاح (${response.code})")
                val body = response.body ?: throw AnimeifyException("لم تصل بيانات المصدر")
                // A malformed server must not allocate an unbounded response on the device.
                val source = body.source()
                if (source.request(4L * 1024 * 1024 + 1)) throw AnimeifyException("بيانات المصدر أكبر من الحد المسموح")
                val bytes = source.readByteArray()
                coroutineContext.ensureActive()
                bytes.toString(Charsets.UTF_8)
            }
        } catch (_: java.io.IOException) {
            throw AnimeifyException("تعذّر الاتصال بمصدر الأنمي، تحقق من الإنترنت وحاول مجدداً")
        }
    }

    companion object {
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

        internal fun titles(element: JsonElement): List<AnimeifyTitle> = (element as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.mapNotNull { o ->
                val id = o.text("AnimeId").ifBlank { o.text("anime_id") }
                if (id.isBlank()) return@mapNotNull null
                AnimeifyTitle(id, o.text("MalId_org").toIntOrNull() ?: o.text("MalId").toIntOrNull(),
                    o.text("EN_Title").ifBlank { o.text("AR_Title") }, o.text("Type").ifBlank { "SERIES" },
                    o.text("Season").toIntOrNull()?.coerceAtLeast(1) ?: 1, o.text("RelationId"))
            }.distinctBy { it.id }

        internal fun parseEpisodes(element: JsonElement): List<AnimeifyEpisode> = (element as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.mapNotNull { o ->
                val number = o.text("Episode")
                if (number.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } != true) return@mapNotNull null
                AnimeifyEpisode(number, o.text("ExtraEpisodes"))
            }.distinctBy { it.number }.sortedBy { it.number.toDouble() }

        internal fun parseServers(o: JsonObject): List<AnimeifyServer> = listOf(
            "FRFhdQ" to "MediaFire · FHD · MP4", "FRLink" to "MediaFire · HD · MP4",
            "FRLowQ" to "MediaFire · SD · MP4", "SVLink" to "Sendvid · HD",
            "SVLowQ" to "Sendvid · SD", "OKLink" to "OK · الجودة المتاحة",
        ).mapNotNull { (key, label) -> o.text(key).takeIf { it.isNotBlank() && it != "0" && it != "1" }
            ?.let { AnimeifyServer(key, label, it) } }

        internal fun mediaFireUrl(html: String): String? {
            val doc = Jsoup.parse(html)
            doc.selectFirst("a#downloadButton[href]")?.attr("href")?.takeIf { validMediaFireDownload(it) }?.let { return it }
            val encoded = doc.selectFirst("[data-scrambled-url]")?.attr("data-scrambled-url") ?: return null
            val decoded = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
            return decoded?.takeIf { validMediaFireDownload(it) }
        }

        private fun validMediaFireDownload(value: String): Boolean = value.toHttpUrlOrNull()?.let {
            it.isHttps && it.host.endsWith(".mediafire.com") && it.host.startsWith("download")
        } == true

        internal fun okVideoUrl(html: String): String? {
            val json = Json { ignoreUnknownKeys = true }
            for (element in Jsoup.parse(html).select("[data-options]")) {
                val url = runCatching {
                    val options = json.parseToJsonElement(element.attr("data-options")).jsonObject
                    val raw = options["flashvars"]?.jsonObject?.get("metadata")?.jsonPrimitive?.content ?: return@runCatching null
                    val videos = json.parseToJsonElement(raw).jsonObject["videos"]?.jsonArray.orEmpty()
                    videos.map { it.jsonObject }.lastOrNull()?.get("url")?.jsonPrimitive?.content
                }.getOrNull()
                if (url != null) return url
            }
            return null
        }
    }
}
