package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
data class FourURef(val kind: String, val id: String, val extension: String = "mp4", val detailType: String? = null, val detailId: Int? = null)
data class FourUChannel(val id: String, val categoryId: String, val name: String, val url: String, val headers: Map<String, String>)

/** Public catalogue and live-channel reads used by the owner's 4U TV 1.1.0 app. */
object FourU {
    const val BASE = "https://ctm.alostoura.live"
    const val MEDIA = "$BASE/media-4u-9d7f2c"
    val account = XtreamAccount(MEDIA, "4u", "4u", "fouru", "4U")
    private const val ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzkzNzkzMjEyLCJleHAiOjE5NTE0NzMyMTJ9.iqm9WGbraabiykSNLiOnk6LS9kLusprYI0Lox568jXw"
    private val http = Net.streams.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()
    private var liveCache: List<FourUChannel>? = null
    private val titleMatches = ConcurrentHashMap<String, JsonObject>()

    private suspend fun read(url: String, publicRead: Boolean = false): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            if (publicRead) { header("apikey", ANON); header("Authorization", "Bearer $ANON") }
        }.build()
        try {
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw XtreamException("تعذّر الاتصال بـ4U (${r.code})")
                val source = r.body?.source() ?: throw XtreamException("لم تصل بيانات 4U")
                if (source.request(32L * 1024 * 1024 + 1)) throw XtreamException("بيانات 4U أكبر من الحد المسموح")
                Json.parseToJsonElement(source.readUtf8())
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: XtreamException) { throw e }
        catch (_: Exception) { throw XtreamException("تعذّر الاتصال بـ4U، حاول مرة ثانية") }
    }

    suspend fun catalog(action: String, params: Map<String, String> = emptyMap()): JsonElement {
        val url = "$MEDIA/player_api.php".toHttpUrl().newBuilder().addQueryParameter("username", "4u").addQueryParameter("password", "4u").addQueryParameter("action", action)
        params.forEach { (k, v) -> url.addQueryParameter(k, v) }
        return read(url.build().toString())
    }

    /** Read one title at a time and stop at the match, instead of waiting for the entire catalogue. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun matches(series: Boolean, names: List<String>, year: Int): List<JsonObject> = withContext(Dispatchers.IO) {
        val keys = names.map { XtreamApi.normalizeTitle(it) }.filter { it.length >= 2 }.toSet()
        if (keys.isEmpty()) return@withContext emptyList()
        val cacheKey = "$series:$year:${keys.sorted()}"
        titleMatches[cacheKey]?.let { return@withContext listOf(it) }
        val action = if (series) "get_series" else "get_vod_streams"
        val url = "$MEDIA/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", "4u").addQueryParameter("password", "4u")
            .addQueryParameter("action", action).build()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw XtreamException("تعذّر الاتصال بـ4U (${response.code})")
                val body = response.body ?: throw XtreamException("لم تصل بيانات 4U")
                var bytes = 0L
                val input = object : java.io.FilterInputStream(body.byteStream()) {
                    override fun read(): Int {
                        val value = super.read()
                        if (value >= 0 && ++bytes > 32L * 1024 * 1024) throw XtreamException("بيانات 4U أكبر من الحد المسموح")
                        return value
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(buffer, offset, length)
                        if (count > 0) bytes += count
                        if (bytes > 32L * 1024 * 1024) throw XtreamException("بيانات 4U أكبر من الحد المسموح")
                        return count
                    }
                }
                val found = Json.decodeToSequence<JsonObject>(input, DecodeSequenceMode.ARRAY_WRAPPED).firstOrNull { row ->
                    val name = row.text4u("name")
                    val release = Regex("(?:19|20)\\d{2}").find(row.text4u("releaseDate").ifBlank { name })?.value?.toIntOrNull()
                    row.text4u(if (series) "series_id" else "stream_id").isNotBlank() &&
                        XtreamApi.normalizeTitle(name) in keys && (year <= 0 || release == null || release == year)
                }
                if (found == null) emptyList() else {
                    if (titleMatches.size > 128) titleMatches.clear()
                    titleMatches[cacheKey] = found
                    listOf(found)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: XtreamException) { throw e }
        catch (_: java.io.InterruptedIOException) { throw XtreamException("استغرق 4U وقتاً طويلاً في الرد؛ حاول مرة ثانية") }
        catch (_: Exception) { throw XtreamException("تعذّر جلب محتوى 4U؛ تحقق من الاتصال وحاول مرة ثانية") }
    }

    suspend fun channels(): List<FourUChannel> {
        liveCache?.let { return it }
        val rows = mutableListOf<FourUChannel>()
        var offset = 0
        while (true) {
            val page = read("$BASE/rest/v1/h9ch_26wd?select=id,category_id,name,stream_url,user_agent,referrer&order=id.asc&offset=$offset&limit=1000", true) as? JsonArray
                ?: throw XtreamException("تعذّر قراءة قنوات 4U")
            for (element in page) {
                val row = element as? JsonObject ?: continue
                val url = row.text4u("stream_url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
                rows += FourUChannel(row.text4u("id"), row.text4u("category_id"), row.text4u("name"), url,
                    buildMap { row.text4u("user_agent").takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }; row.text4u("referrer").takeIf { it.isNotBlank() }?.let { put("Referer", it) } })
            }
            if (page.size < 1000) break
            offset += page.size
            if (offset > 100000) throw XtreamException("قائمة قنوات 4U أكبر من الحد المسموح")
        }
        return rows.distinctBy { it.id }.also { liveCache = it }
    }

    suspend fun liveApi(action: String?, params: Map<String, String>): JsonElement = when (action) {
        "get_live_categories" -> {
            val rows = read("$BASE/rest/v1/c5tg_84zr?select=id,name&order=id.asc&limit=1000", true) as? JsonArray ?: JsonArray(emptyList())
            JsonArray(rows.mapNotNull { it as? JsonObject }.map { r -> buildJsonObject { put("category_id", r.text4u("id")); put("category_name", r.text4u("name")) } })
        }
        "get_live_streams" -> JsonArray(channels().filter { params["category_id"] == null || it.categoryId == params["category_id"] }.mapIndexed { index, c ->
            buildJsonObject { put("stream_id", c.id); put("name", c.name); put("category_id", c.categoryId); put("num", index + 1); put("direct_source", c.url) }
        })
        "get_short_epg" -> buildJsonObject { put("epg_listings", JsonArray(emptyList())) }
        else -> throw XtreamException("هذا الخيار غير متاح في 4U")
    }

    fun channelHeaders(id: String): Map<String, String> = liveCache?.firstOrNull { it.id == id }?.headers.orEmpty()

    suspend fun resolve(ref: FourURef): Pair<String, Map<String, String>> {
        if (!ref.id.matches(Regex("[0-9]+"))) throw XtreamException("معرّف 4U غير صالح")
        if (ref.kind == "live") {
            val channel = channels().firstOrNull { it.id == ref.id } ?: throw XtreamException("القناة لم تعد متاحة في 4U")
            return channel.url to channel.headers
        }
        return mediaUrl(ref) to emptyMap()
    }

    fun mediaUrl(ref: FourURef): String {
        require(ref.kind in setOf("movie", "series") && ref.id.matches(Regex("[0-9]+")) && ref.extension.matches(Regex("[a-zA-Z0-9]+")))
        return "$MEDIA/${ref.kind}/4u/4u/${ref.id}.${ref.extension}"
    }
}

fun JsonObject.text4u(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
