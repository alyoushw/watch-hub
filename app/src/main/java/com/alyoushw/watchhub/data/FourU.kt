package com.alyoushw.watchhub.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var liveCache: List<FourUChannel>? = null

    @Volatile private var cacheDir: File? = null
    private data class Cached(val at: Long, val value: JsonElement)
    private val saved = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val indexes = ConcurrentHashMap<Boolean, Pair<JsonArray, Map<String, List<JsonObject>>>>()

    fun initialize(context: Context) { cacheDir = File(context.cacheDir, "fouru-v1") }

    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    /** The saved list from memory, else disk, whatever its age (null if never downloaded). */
    private fun savedList(key: String): Cached? {
        saved[key]?.let { return it }
        val file = cacheDir?.let { File(it, "$key.json") } ?: return null
        if (file.length() !in 1..(32L * 1024 * 1024)) return null
        val value = runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull()?.takeIf { it is JsonArray } ?: return null
        return Cached(file.lastModified(), value).also { saved[key] = it }
    }

    /** Downloads the list and stores it in memory and on disk. Never caches errors. */
    private suspend fun download(key: String, load: suspend () -> JsonElement): JsonElement {
        val value = load()
        if (value !is JsonArray) throw XtreamException("تعذّر قراءة قائمة 4U")
        saved[key] = Cached(System.currentTimeMillis(), value)
        runCatching {
            val file = cacheDir?.let { File(it, "$key.json") }
            if (file != null) {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, "$key.tmp")
                temp.writeText(value.toString())
                if (!temp.renameTo(file)) temp.delete()
            }
        }
        return value
    }

    /**
     * Serve the saved list at once — even when it is older than [ttl] — and refresh it quietly in
     * the background. Only the very first use (nothing cached yet) waits for the ~11 MB download;
     * after that opening 4U is instant, and the list still updates on its own. Opening a title page
     * no longer downloads anything, so browsing stays fast.
     */
    private suspend fun cached(key: String, ttl: Long, load: suspend () -> JsonElement): JsonElement = withContext(Dispatchers.IO) {
        val existing = savedList(key)
        if (existing != null) {
            if (System.currentTimeMillis() - existing.at !in 0..ttl && refreshing.add(key)) {
                scope.launch {
                    try { locks.getOrPut(key) { Mutex() }.withLock { download(key, load) } }
                    catch (_: Exception) { /* keep serving the saved list */ }
                    finally { refreshing.remove(key) }
                }
            }
            return@withContext existing.value
        }
        locks.getOrPut(key) { Mutex() }.withLock {
            savedList(key)?.let { if (System.currentTimeMillis() - it.at in 0..ttl) return@withLock it.value }
            download(key, load)
        }
    }

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

    /** Index each kind once; another title no longer downloads the same 10–16 MB list. */
    /** The movie or series list, downloaded once and reused from memory/disk for 6 hours. */
    private suspend fun catalogueRows(series: Boolean): JsonArray {
        val action = if (series) "get_series" else "get_vod_streams"
        return cached(action, TimeUnit.HOURS.toMillis(6)) {
            val raw = catalog(action) as? JsonArray ?: throw XtreamException("تعذّر قراءة محتوى 4U")
            val fields = setOf("name", "series_id", "stream_id", "cover", "stream_icon", "releaseDate", "release_date", "container_extension")
            JsonArray(raw.mapNotNull { it as? JsonObject }.map { JsonObject(it.filterKeys { key -> key in fields }) })
        } as JsonArray
    }

    suspend fun matches(series: Boolean, names: List<String>, year: Int): List<JsonObject> = withContext(Dispatchers.IO) {
        val keys = names.map { XtreamApi.normalizeTitle(it) }.filter { it.length >= 2 }.toSet()
        if (keys.isEmpty()) return@withContext emptyList()
        val rows = catalogueRows(series)
        val old = indexes[series]
        val index = if (old != null && old.first === rows) old.second else rows.mapNotNull { it as? JsonObject }
            .groupBy { XtreamApi.normalizeTitle(it.text4u("name")) }.also { indexes[series] = rows to it }
        val found = keys.asSequence().flatMap { index[it].orEmpty().asSequence() }.firstOrNull { row ->
            val release = Regex("(?:19|20)\\d{2}").find(row.text4u("releaseDate").ifBlank { row.text4u("release_date").ifBlank { row.text4u("name") } })?.value?.toIntOrNull()
            row.text4u(if (series) "series_id" else "stream_id").isNotBlank() && (year <= 0 || release == null || release == year)
        }
        listOfNotNull(found)
    }

    suspend fun channels(): List<FourUChannel> = withContext(Dispatchers.IO) {
        val all = cached("live", TimeUnit.MINUTES.toMillis(15)) {
            coroutineScope {
                val rows = mutableListOf<JsonElement>()
                var offset = 0
                while (offset <= 100000) {
                    // Four bounded requests instead of waiting for every page in turn.
                    val pages = (0 until 4).map { page -> async {
                        read("$BASE/rest/v1/h9ch_26wd?select=id,category_id,name,stream_url,user_agent,referrer&order=id.asc&offset=${offset + page * 1000}&limit=1000", true) as? JsonArray
                            ?: throw XtreamException("تعذّر قراءة قنوات 4U")
                    } }.awaitAll()
                    var complete = false
                    for (page in pages) {
                        rows.addAll(page)
                        if (page.size < 1000) { complete = true; break }
                    }
                    if (complete) return@coroutineScope JsonArray(rows)
                    offset += 4000
                }
                throw XtreamException("قائمة قنوات 4U أكبر من الحد المسموح")
            }
        } as JsonArray
        all.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val url = row.text4u("stream_url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
            FourUChannel(row.text4u("id"), row.text4u("category_id"), row.text4u("name"), url,
                buildMap { row.text4u("user_agent").takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }; row.text4u("referrer").takeIf { it.isNotBlank() }?.let { put("Referer", it) } })
        }.distinctBy { it.id }.also { liveCache = it }
    }

    suspend fun liveApi(action: String?, params: Map<String, String>): JsonElement = when (action) {
        "get_live_categories" -> {
            val rows = cached("categories", TimeUnit.MINUTES.toMillis(15)) { read("$BASE/rest/v1/c5tg_84zr?select=id,name&order=id.asc&limit=1000", true) } as JsonArray
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
