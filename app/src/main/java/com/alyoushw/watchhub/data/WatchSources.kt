package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class SourceQuery(
    val type: MediaType, val tmdbId: Int, val originalTitle: String, val year: Int,
    val season: Int? = null, val episode: Int? = null, val imdbId: String? = null, val searchTitle: String? = null,
    val seasonYear: Int? = null,
    val localizedTitle: String? = null,
) {
    fun websiteTitles(): List<String> = listOfNotNull(localizedTitle, searchTitle, originalTitle)
        .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { titleKey(it) }
        .sortedBy { name -> if (name.any { it in '\u0600'..'\u06ff' }) 0 else 1 }

    val valid: Boolean get() = type in setOf(MediaType.MOVIE, MediaType.TV) && tmdbId > 0 &&
        originalTitle.isNotBlank() && year in 1888..2200 &&
        if (type == MediaType.TV) season != null && season >= 0 && episode != null && episode > 0
        else season == null && episode == null

    fun matches(other: SourceQuery): Boolean = valid && other.valid && type == other.type && tmdbId == other.tmdbId &&
        year == other.year && season == other.season && episode == other.episode &&
        titleKey(originalTitle) == titleKey(other.originalTitle) &&
        (imdbId.isNullOrBlank() || other.imdbId.isNullOrBlank() || imdbId == other.imdbId)

    companion object {
        fun titleKey(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}

@Serializable enum class SourceTransport { MP4, HLS, MKV, TORRENT, MAGNET }

@Serializable
data class SourceVariant(val id: String, val label: String, val url: String, val transport: SourceTransport,
    val httpHeaders: Map<String, String> = emptyMap())

@Serializable
data class SourceSubtitle(val id: String, val label: String, val language: String, val url: String, val format: String = "vtt")

/** A website page is an alternative to a native stream, never a video URI. */
@Serializable
data class SourceWebLink(val label: String, val url: String, val download: Boolean = false)

@Serializable
enum class SourceHealth { PLAYABLE, UNAVAILABLE, BLOCKED, NETWORK_ERROR, UNSUPPORTED, UNVERIFIED }

@Serializable
data class SourceIssue(val server: String, val health: SourceHealth, val httpStatus: Int? = null)

data class SourceCheck(val health: SourceHealth, val httpStatus: Int? = null) {
    val playable: Boolean get() = health == SourceHealth.PLAYABLE
    fun issue(server: String) = SourceIssue(server, health, httpStatus)
}

internal fun sourceHttpCheck(code: Int) = SourceCheck(when (code) {
    401, 403, 429 -> SourceHealth.BLOCKED
    404, 410 -> SourceHealth.UNAVAILABLE
    in 500..599 -> SourceHealth.UNAVAILABLE
    else -> SourceHealth.UNVERIFIED
}, code)

internal fun sourceFailure(server: String, error: Exception): SourceIssue =
    (if (error is SourceException && error.health != null) SourceCheck(error.health, error.httpStatus)
    else if (error is SourceException && error.httpStatus != null) sourceHttpCheck(error.httpStatus)
    else SourceCheck(if (error is java.io.IOException) SourceHealth.NETWORK_ERROR else SourceHealth.UNVERIFIED)).issue(server)

internal fun checkedSourcePage(doc: org.jsoup.nodes.Document): org.jsoup.nodes.Document {
    val title = doc.title().lowercase(Locale.ROOT)
    if (title.startsWith("just a moment") || title.startsWith("attention required") || title.trim() in setOf("access denied", "verify you are human") ||
        doc.select("#challenge-form").isNotEmpty())
        throw SourceException("الموقع يطلب تحققاً أو يمنع الوصول؛ لا يمكن تأكيد توفر العمل الآن", health = SourceHealth.BLOCKED)
    return doc
}

@Serializable
data class WatchSource(
    val id: String, val providerId: String, val providerName: String, val query: SourceQuery,
    val variants: List<SourceVariant>, val subtitles: List<SourceSubtitle> = emptyList(),
    val attribution: String, val licenceUrl: String,
    val webLinks: List<SourceWebLink> = emptyList(),
    val issues: List<SourceIssue> = emptyList(),
    val matchVerified: Boolean = true,
) {
    fun compatible(query: SourceQuery): Boolean = this.query.matches(query) && Regex("[\\p{L}\\p{N}_.:-]{1,160}").matches(id) &&
        Regex("[a-zA-Z0-9_.:-]{1,80}").matches(providerId) && attribution.isNotBlank() && licenceUrl.toHttpUrlOrNull() != null &&
        (matchVerified || variants.isEmpty()) &&
        (variants.isNotEmpty() || webLinks.isNotEmpty()) && webLinks.all { it.label.isNotBlank() && it.url.toHttpUrlOrNull()?.isHttps == true } && variants.all { variant ->
            variant.id.isNotBlank() && if (variant.transport == SourceTransport.MAGNET) magnetHash(variant.url) != null
            else variant.url.toHttpUrlOrNull() != null
        } && subtitles.all { it.url.toHttpUrlOrNull() != null && it.format in setOf("srt", "vtt") }
}

/** Persist identity only; resolve current provider URLs again on every playback/retry. */
@Serializable
data class WatchSourceRef(val providerId: String, val sourceId: String, val query: SourceQuery, val variantId: String, val providerName: String? = null)

interface WatchSourceProvider {
    suspend fun search(query: SourceQuery): List<WatchSource>
    suspend fun find(query: SourceQuery, id: String): WatchSource? = search(query).firstOrNull { it.id == id }
    suspend fun findVariant(query: SourceQuery, id: String, variantId: String): WatchSource? = find(query, id)
}

class SourceException(message: String, val httpStatus: Int? = null, val health: SourceHealth? = null) : Exception(message)

class ManifestSourceProvider(
    private val endpoint: String,
    private val http: OkHttpClient = SourceHttp.client,
) : WatchSourceProvider {
    private val json = catalogueJson
    override suspend fun search(query: SourceQuery): List<WatchSource> = withContext(Dispatchers.IO) {
        val base = endpoint.toHttpUrlOrNull() ?: throw SourceException("أدخل رابط HTTP أو HTTPS لكتالوج المصادر")
        val url = base.newBuilder().setQueryParameter("tmdb_id", query.tmdbId.toString())
            .setQueryParameter("type", query.type.path).setQueryParameter("original_title", query.originalTitle)
            .setQueryParameter("year", query.year.toString())
            .apply {
                query.season?.let { setQueryParameter("season", it.toString()) }
                query.episode?.let { setQueryParameter("episode", it.toString()) }
                query.imdbId?.let { setQueryParameter("imdb_id", it) }
            }.build()
        val payload = SourceHttp.read(http, url.toString(), 2 * 1024 * 1024)
        val entries = try { json.decodeFromString(ListSerializer(WatchSource.serializer()), payload) }
        catch (_: Exception) { throw SourceException("صيغة كتالوج المصادر غير صالحة") }
        entries.filter { it.compatible(query) }
    }
}

private val catalogueJson = Json { ignoreUnknownKeys = true }

/** Redistributable Blender open movies; no entitlement or subscription stream extraction. */
class OpenFilmSourceProvider : WatchSourceProvider {
    override suspend fun search(query: SourceQuery): List<WatchSource> = catalogue.filter { it.compatible(query) }

    companion object {
        private val catalogue = listOf(
            WatchSource("sintel", "blender", "Blender Open Movies", SourceQuery(MediaType.MOVIE, 45745, "Sintel", 2010),
                listOf(SourceVariant("mp4", "MP4", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4", SourceTransport.MP4),
                    SourceVariant("mkv", "MKV · الملف الأصلي", "https://download.blender.org/durian/movies/Sintel.2010.1080p.mkv", SourceTransport.MKV)),
                attribution = "Sintel © Blender Foundation · CC BY 3.0", licenceUrl = "https://durian.blender.org/sharing/"),
            WatchSource("big-buck-bunny", "blender", "Blender Open Movies", SourceQuery(MediaType.MOVIE, 10378, "Big Buck Bunny", 2008),
                listOf(SourceVariant("mp4", "MP4", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", SourceTransport.MP4),
                    SourceVariant("hls", "HLS · جودة متكيّفة", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", SourceTransport.HLS)),
                attribution = "Big Buck Bunny © Blender Foundation · CC BY 3.0", licenceUrl = "https://peach.blender.org/about/"),
        )
    }
}

data class SourceAvailability(val providerId: String, val episodes: Map<Int, List<Int>>, val message: String)
internal class SourceAvailabilityException(val availability: SourceAvailability) : Exception(availability.message)
data class SourceSearch(val sources: List<WatchSource>, val errors: List<String>, val availability: List<SourceAvailability> = emptyList())
data class ResolvedWatchSource(val source: WatchSource, val variant: SourceVariant, val uri: String)

/** Search, compatibility checks and playback preparation remain separate. */
class WatchSources(private val providers: () -> List<WatchSourceProvider>, private val probe: SourceProbe = StreamProbe(verifyHlsSegments = true)) {
    suspend fun search(query: SourceQuery): SourceSearch = coroutineScope {
        if (!query.valid) return@coroutineScope SourceSearch(emptyList(), listOf("بيانات العمل أو الموسم والحلقة غير مكتملة"))
        val results = providers().map { provider -> async {
            try { SourceSearch(provider.search(query).filter { it.compatible(query) }, emptyList()) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: SourceAvailabilityException) { SourceSearch(emptyList(), emptyList(), listOf(e.availability)) }
            catch (e: Exception) { SourceSearch(emptyList(), listOf(e.message ?: "تعذّر الاتصال بأحد المصادر")) }
        } }.awaitAll()
        val valid = results.flatMap { it.sources }.distinctBy { "${it.providerId}:${it.id}" }.map { source -> async {
            val issues = source.issues.toMutableList()
            val variants = source.variants.mapNotNull { variant ->
                if (variant.transport in setOf(SourceTransport.TORRENT, SourceTransport.MAGNET)) variant else {
                    val check = probe.inspect(variant)
                    if (check.playable) variant else { issues += check.issue(variant.label); null }
                }
            }
            source.copy(variants = variants, issues = issues.distinct()).takeIf { variants.isNotEmpty() || source.webLinks.isNotEmpty() }
        } }.awaitAll().filterNotNull()
        SourceSearch(valid, results.flatMap { it.errors }, results.flatMap { it.availability })
    }

    suspend fun resolve(ref: WatchSourceRef, engines: PlaybackEngines, progress: (Float) -> Unit = {}): ResolvedWatchSource {
        val found = coroutineScope {
            providers().map { provider -> async {
                try { provider.findVariant(ref.query, ref.sourceId, ref.variantId)?.takeIf { it.providerId == ref.providerId && it.compatible(ref.query) } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            } }.awaitAll().firstOrNull { it != null }
        } ?: throw SourceException("المصدر لم يعد متاحاً لهذا العمل؛ اختر مصدراً آخر")
        val variant = found.variants.firstOrNull { it.id == ref.variantId }
            ?: throw SourceException("الجودة المطلوبة لم تعد متاحة")
        return ResolvedWatchSource(found, variant, engines.prepare(variant, ref.query, progress))
    }
}

fun interface SourceProbe {
    suspend fun compatible(variant: SourceVariant): Boolean
    suspend fun inspect(variant: SourceVariant): SourceCheck =
        SourceCheck(if (compatible(variant)) SourceHealth.PLAYABLE else SourceHealth.UNVERIFIED)
}

class StreamProbe(private val http: OkHttpClient = SourceHttp.client, private val verifyHlsSegments: Boolean = false) : SourceProbe {
    override suspend fun compatible(variant: SourceVariant): Boolean = inspect(variant).playable
    override suspend fun inspect(variant: SourceVariant): SourceCheck = withContext(Dispatchers.IO) {
        if (variant.transport !in setOf(SourceTransport.MP4, SourceTransport.HLS, SourceTransport.MKV)) return@withContext SourceCheck(SourceHealth.UNSUPPORTED)
        try {
            val host = variant.url.toHttpUrlOrNull()?.host
            val transport = if (variant.httpHeaders.isEmpty()) http else http.newBuilder().addNetworkInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().apply {
                    variant.httpHeaders.forEach { (name, value) ->
                        if (request.url.host == host || name.equals("User-Agent", true)) header(name, value)
                    }
                }.build())
            }.build()
            val request = Request.Builder().url(variant.url).header("Range", "bytes=0-4095").build()
            transport.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use sourceHttpCheck(response.code)
                val body = response.body ?: return@use SourceCheck(SourceHealth.UNAVAILABLE)
                val source = body.source()
                // Wait for a bounded prefix even when a slow server splits the playlist across packets.
                source.request(4096)
                val bytes = source.readByteArray(minOf(4096L, source.buffer.size))
                if (!signatureMatches(variant.transport, bytes)) {
                    val text = bytes.toString(Charsets.UTF_8).lowercase(Locale.ROOT)
                    return@use SourceCheck(if (listOf("just a moment", "verify you are human", "access denied", "cf-chl-").any { it in text })
                        SourceHealth.BLOCKED else SourceHealth.UNSUPPORTED)
                }
                if (verifyHlsSegments && variant.transport == SourceTransport.HLS)
                    playableHls(transport, response.request.url, bytes.toString(Charsets.UTF_8))
                else SourceCheck(SourceHealth.PLAYABLE)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { SourceCheck(if (e is java.io.IOException) SourceHealth.NETWORK_ERROR else SourceHealth.UNVERIFIED) }
    }

    /** Some hosts return a valid master but blocked child playlists or image-wrapped segments. */
    private fun playableHls(client: OkHttpClient, base: okhttp3.HttpUrl, text: String, depth: Int = 0): SourceCheck {
        val unsupported = SourceCheck(SourceHealth.UNSUPPORTED)
        if (depth > 3 || !signatureMatches(SourceTransport.HLS, text.toByteArray(Charsets.UTF_8))) return unsupported
        val uri = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith('#') } ?: return unsupported
        val next = base.resolve(uri)?.takeIf { it.isHttps } ?: return unsupported
        val media = prefix(client, next)
        media.failure?.let { return it }
        if (media.bytes.toString(Charsets.UTF_8).trimStart().startsWith("#EXTM3U"))
            return playableHls(client, media.url, media.bytes.toString(Charsets.UTF_8), depth + 1)
        if (!text.contains("#EXTINF:")) return unsupported
        val init = Regex("""#EXT-X-MAP:[^\r\n]*URI="([^"]+)"""").find(text)?.groupValues?.get(1)
        if (init != null) {
            val initUrl = base.resolve(init)?.takeIf { it.isHttps } ?: return unsupported
            val initData = prefix(client, initUrl)
            initData.failure?.let { return it }
            if (!isoMedia(initData.bytes)) return unsupported
        }
        val bytes = media.bytes
        val ts = bytes.size >= 188 && bytes[0] == 0x47.toByte() && (bytes.size <= 188 || bytes[188] == 0x47.toByte())
        return SourceCheck(if (ts || isoMedia(bytes)) SourceHealth.PLAYABLE else SourceHealth.UNSUPPORTED)
    }

    private fun isoMedia(bytes: ByteArray): Boolean = bytes.size >= 8 &&
        String(bytes, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "styp", "moof", "sidx")

    private data class Prefix(val url: okhttp3.HttpUrl, val bytes: ByteArray = byteArrayOf(), val failure: SourceCheck? = null)
    private fun prefix(client: OkHttpClient, url: okhttp3.HttpUrl): Prefix =
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-4095").build()).execute().use { response ->
            if (!response.isSuccessful) return@use Prefix(response.request.url, failure = sourceHttpCheck(response.code))
            val source = response.body?.source() ?: return@use Prefix(response.request.url, failure = SourceCheck(SourceHealth.UNAVAILABLE))
            source.request(4096)
            Prefix(response.request.url, source.readByteArray(minOf(4096L, source.buffer.size)))
        }

    companion object {
        fun signatureMatches(type: SourceTransport, bytes: ByteArray): Boolean = when (type) {
            SourceTransport.MP4 -> bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
            SourceTransport.MKV -> bytes.take(4).map { it.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3)
            SourceTransport.HLS -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart().let {
                it.startsWith("#EXTM3U") && ("#EXTINF:" in it || "#EXT-X-STREAM-INF:" in it) &&
                    !it.contains("METHOD=SAMPLE-AES", true) && !it.contains("KEYFORMAT=", true)
            }
            else -> false
        }
    }
}

internal object SourceHttp {
    val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    fun read(http: OkHttpClient, url: String, limit: Int): String = bytes(http, url, limit).toString(Charsets.UTF_8)
    fun bytes(http: OkHttpClient, url: String, limit: Int): ByteArray = http.newCall(Request.Builder().url(url).build()).execute().use { res ->
        if (!res.isSuccessful) throw SourceException("المصدر أعاد HTTP ${res.code}")
        val body = res.body ?: throw SourceException("رد المصدر فارغ")
        if (body.contentLength() > limit) throw SourceException("رد المصدر أكبر من الحد المسموح")
        val source = body.source()
        if (source.request(limit.toLong() + 1)) throw SourceException("رد المصدر أكبر من الحد المسموح")
        source.readByteArray()
    }
}

fun magnetHash(magnet: String): String? {
    if (!magnet.startsWith("magnet:?", true)) return null
    val hash = try {
        magnet.substringAfter('?').substringBefore('#').split('&').firstNotNullOfOrNull { pair ->
            if (!pair.startsWith("xt=", true)) null else java.net.URLDecoder.decode(pair.substringAfter('='), "UTF-8")
                .takeIf { it.startsWith("urn:btih:", true) }?.substring(9)
        }
    } catch (_: Exception) { null } ?: return null
    if (Regex("[a-fA-F0-9]{40}").matches(hash)) return hash.lowercase(Locale.ROOT).takeUnless { it.all { c -> c == '0' } }
    if (!Regex("[A-Za-z2-7]{32}").matches(hash)) return null
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var bits = 0
    var buffer = 0
    val bytes = mutableListOf<Int>()
    hash.uppercase(Locale.ROOT).forEach { char ->
        buffer = (buffer shl 5) or alphabet.indexOf(char)
        bits += 5
        if (bits >= 8) { bits -= 8; bytes += (buffer shr bits) and 255 }
    }
    return bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }.takeUnless { it.all { c -> c == '0' } }
}

/** BEP-9 exact metadata source, still checked against the Magnet's info hash by the engine. */
fun magnetExactSource(magnet: String): String? = try {
    magnet.substringAfter('?', "").substringBefore('#').split('&').firstNotNullOfOrNull { pair ->
        if (!pair.startsWith("xs=", true)) null else java.net.URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            .takeIf { it.toHttpUrlOrNull() != null }
    }
} catch (_: Exception) { null }
