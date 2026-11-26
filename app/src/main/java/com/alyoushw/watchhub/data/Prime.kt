package com.alyoushw.watchhub.data

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only identities go into history/cloud. Session URLs, cookies and access keys stay out. */
@Serializable
data class PrimeRef(val query: SourceQuery? = null, val channelId: String? = null)

data class PrimeStream(val url: String, val headers: Map<String, String>, val mimeType: String?)
data class PrimeChannel(val id: String, val name: String, val image: String?, val group: String)

/** Prime's own client configuration, catalog and primary VOD provider. */
object Prime {
    private lateinit var context: Context
    private val json = Json { ignoreUnknownKeys = true }
    private val configLock = Mutex()
    private val catalogLock = Mutex()
    private var configuration: JsonObject? = null
    private var configuredAt = 0L
    private var catalog: Pair<JsonArray, JsonArray>? = null
    private var catalogAt = 0L
    private val cookies = object : CookieJar {
        private val values = mutableMapOf<String, List<Cookie>>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            values[url.host] = (values[url.host].orEmpty() + cookies).associateBy { it.name }.values.toList()
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            values.values.flatten().filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val PACKAGE = "primetv.ott"
    private const val APK_SHA256 = "28884c8266619152ee0520beb47195f1d2d2dbcc10fcae6411bb33d2d5a729b0"
    private val http = OkHttpClient.Builder().cookieJar(cookies).callTimeout(35, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS).build()
    private val downloadHttp = http.newBuilder().callTimeout(180, TimeUnit.SECONDS).build()

    fun initialize(context: Context) { this.context = context.applicationContext }

    private fun read(request: Request, limit: Long = 8L * 1024 * 1024): String =
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SourceException("تعذّر الاتصال بـ Prime (HTTP ${response.code})")
            val body = response.body ?: throw SourceException("رد Prime فارغ")
            val source = body.source()
            if (source.request(limit + 1)) throw SourceException("رد Prime أكبر من الحد المسموح")
            source.readUtf8()
        }

    /** Read public client identifiers from the original APK, without installing/running its code.
     * No copied API key or service credential is committed to Watch Hub. The pinned APK is cached
     * privately and fetched once; later launches only fetch the small current configuration. */
    @Suppress("DEPRECATION")
    private suspend fun clientSettings(): JsonObject {
        val archive = File(context.filesDir, "prime-client-2.4.5.apk")
        if (!archive.exists()) {
            val partial = File(context.filesDir, "prime-client.download")
            try {
                val request = Request.Builder().url("https://drive.usercontent.google.com/download?id=1cWoOvnHlaD1I6tNVwUaw7vYlMbTPgNjr&export=download&confirm=t").build()
                val digest = MessageDigest.getInstance("SHA-256")
                downloadHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw SourceException("تعذّرت تهيئة Prime؛ حاول مرة ثانية")
                    val input = response.body?.byteStream() ?: throw SourceException("ملف إعدادات Prime فارغ")
                    input.use { stream -> partial.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        var total = 0L
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 50L * 1024 * 1024) throw SourceException("ملف Prime غير متوقع")
                            digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                    } }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (hash != APK_SHA256) throw SourceException("تغيّرت نسخة Prime الأصلية؛ يلزم تحديث الربط")
                if (!partial.renameTo(archive)) throw SourceException("تعذّر حفظ تهيئة Prime")
            } finally { partial.delete() }
        }
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(archive.path, PackageManager.GET_META_DATA)
            ?: throw SourceException("تعذّر قراءة إعدادات Prime")
        if (info.packageName != PACKAGE) throw SourceException("ملف Prime غير صحيح")
        val app = info.applicationInfo ?: throw SourceException("إعدادات Prime ناقصة")
        app.sourceDir = archive.path; app.publicSourceDir = archive.path
        val resources = pm.getResourcesForApplication(app)
        fun value(name: String): String {
            val id = resources.getIdentifier(name, "string", PACKAGE)
            if (id == 0) throw SourceException("إعدادات Prime ناقصة")
            return resources.getString(id)
        }
        return buildJsonObject {
            put("apiKey", value("google_api_key")); put("appId", value("google_app_id"))
            put("projectNumber", value("gcm_defaultSenderId"))
        }
    }

    private suspend fun config(): JsonObject = configLock.withLock {
        configuration?.takeIf { System.currentTimeMillis() - configuredAt < 15 * 60_000 }?.let { return@withLock it }
        val client = clientSettings()
        val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/${client.text("projectNumber")}/namespaces/firebase:fetch"
            .toHttpUrl().newBuilder().addQueryParameter("key", client.text("apiKey")).build()
        val body = buildJsonObject {
            put("appId", client.text("appId")); put("packageName", PACKAGE)
            put("appInstanceId", UUID.randomUUID().toString().replace("-", "").take(22))
            put("appVersion", "2.4.5"); put("sdkVersion", "22.1.2"); put("languageCode", "en-US")
            put("platformVersion", android.os.Build.VERSION.SDK_INT.toString())
        }
        val root = json.parseToJsonElement(read(Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())).jsonObject
        val entries = root["entries"] as? JsonObject ?: throw SourceException("تعذّر تحميل إعدادات Prime")
        if (entries.text("app_active") == "false" || entries.text("backend_enabled") == "false")
            throw SourceException("Prime متوقف مؤقتاً من المصدر")
        configuration = entries; configuredAt = System.currentTimeMillis(); entries
    }

    private suspend fun table(name: String, config: JsonObject): JsonArray {
        val host = config.text("supabase_url").trimEnd('/')
        val key = config.text("supabase_key")
        if (host.isBlank() || key.isBlank()) throw SourceException("كتالوج Prime غير متاح حالياً")
        val rows = mutableListOf<JsonElement>()
        var offset = 0
        while (offset < 30000) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val url = "$host/rest/v1/$name?select=*&order=id.asc&limit=1000&offset=$offset"
            val page = json.parseToJsonElement(read(Request.Builder().url(url).header("apikey", key)
                .header("Authorization", "Bearer $key").build())).jsonArray
            rows.addAll(page)
            if (page.size < 1000) return JsonArray(rows)
            offset += 1000
        }
        throw SourceException("كتالوج Prime أكبر من الحد المسموح")
    }

    private suspend fun liveCatalog(): Pair<JsonArray, JsonArray> = catalogLock.withLock {
        catalog?.takeIf { System.currentTimeMillis() - catalogAt < 10 * 60_000 }?.let { return@withLock it }
        val c = config()
        val data = coroutineScope {
            val channels = async { table("channels", c) }
            val sources = async { table("channel_sources", c) }
            channels.await() to sources.await()
        }
        catalog = data; catalogAt = System.currentTimeMillis(); data
    }

    suspend fun channels(): List<PrimeChannel> = withContext(Dispatchers.IO) {
        liveCatalog().first.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            if (!row.active()) return@mapNotNull null
            PrimeChannel(row.text("id"), row.text("name"), row.text("logo_url").takeIf { it.isNotBlank() },
                row.text("category").ifBlank { "عام" })
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    suspend fun resolve(ref: PrimeRef): PrimeStream = withContext(Dispatchers.IO) {
        ref.channelId?.let { id ->
            val data = liveCatalog()
            val channel = data.first.mapNotNull { it as? JsonObject }.firstOrNull { it.text("id") == id && it.active() }
                ?: throw SourceException("القناة لم تعد موجودة في Prime")
            val source = data.second.mapNotNull { it as? JsonObject }
                .filter { it.text("channel_id") == id && it.active() && it.text("url").startsWith("http") }
                .sortedBy { it["sort_order"]?.jsonPrimitive?.intOrNull ?: 0 }
                .firstOrNull { it.text("drm_scheme").lowercase() in setOf("", "null", "none") }
            val url = source?.text("url") ?: channel.text("m3u8_url").takeIf { it.startsWith("http") }
                ?: throw SourceException("هذه القناة تحتاج ترخيص تشغيل من Prime؛ لا يتوفر رابط غير محمي")
            val headers = buildMap {
                source?.text("user_agent")?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                source?.text("referrer")?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            }
            return@withContext PrimeStream(url, headers, if (url.substringBefore('?').endsWith(".mpd")) "application/dash+xml" else null)
        }
        val query = ref.query ?: throw SourceException("بيانات العمل في Prime ناقصة")
        if (query.tmdbId <= 0 || query.type !in setOf(MediaType.TV, MediaType.MOVIE)) throw SourceException("تعذّر تحديد العمل في Prime")
        val base = config().text("vixsrc_base_url").trimEnd('/').takeIf { it.startsWith("https://") }
            ?: throw SourceException("مزوّد الأفلام في Prime متوقف حالياً")
        val path = if (query.type == MediaType.MOVIE) "/api/movie/${query.tmdbId}" else "/api/tv/${query.tmdbId}/${query.season ?: 1}/${query.episode ?: 1}"
        fun request(url: String, referer: String) = Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer).build()
        val result = json.parseToJsonElement(read(request("$base$path?lang=en&_=${System.currentTimeMillis()}", "$base/"))).jsonObject
        val embed = base.toHttpUrl().resolve(result.text("src"))?.takeIf { it.host == base.toHttpUrl().host }
            ?: throw SourceException("هذا العمل غير متاح في Prime حالياً")
        val html = read(request(embed.toString(), "$base/"))
        val master = html.substringAfter("window.masterPlaylist", "").substringBefore("</script>").take(20000)
        if (master.isBlank()) throw SourceException("تعذّر الحصول على فيديو Prime لهذا العمل")
        val playlist = Regex("""url\s*:\s*['"]([^'"]+)['"]""").find(master)?.groupValues?.get(1)
            ?: throw SourceException("رابط فيديو Prime غير متاح")
        val advertised = Regex("""window\.streams\s*=\s*(\[.*?\]);""").find(html)?.groupValues?.get(1)
            ?.let { json.parseToJsonElement(it) as? JsonArray }?.mapNotNull { (it as? JsonObject)?.text("url") }.orEmpty()
        val candidates = (advertised + playlist).distinct()
        var selected: HttpUrl? = null
        for (candidate in candidates) {
            val builder = (embed.resolve(candidate) ?: continue).newBuilder()
            for (name in listOf("token", "expires", "asn")) {
                Regex("""['"]$name['"]\s*:\s*['"]([^'"]*)['"]""").find(master)?.groupValues?.get(1)
                    ?.takeIf { it.isNotEmpty() }?.let { builder.setQueryParameter(name, it) }
            }
            val stream = builder.setQueryParameter("lang", "en").build()
            try {
                val playlistText = read(request(stream.toString(), embed.toString()))
                if (playlistText.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")) { selected = stream; break }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Try the next server advertised by Prime's provider. */ }
        }
        val stream = selected ?: throw SourceException("Prime لم يُرجع فيديو صالحاً؛ حاول مرة ثانية")
        val headers = buildMap {
            put("User-Agent", UA); put("Referer", embed.toString())
            cookies.loadForRequest(stream).takeIf { it.isNotEmpty() }?.let { put("Cookie", it.joinToString("; ") { c -> "${c.name}=${c.value}" }) }
        }
        PrimeStream(stream.toString(), headers, "application/x-mpegURL")
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.active(): Boolean = text("is_active") != "false" && text("is_deleted") != "true"
}
