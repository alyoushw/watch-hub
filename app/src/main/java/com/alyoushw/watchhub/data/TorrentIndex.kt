package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.Locale

data class TorrentIndexRecord(
    val hash: String, val title: String, val imdbId: String, val seeders: Int,
    val id: String = "", val size: Long = 0,
)

interface TorrentIndex {
    suspend fun search(query: SourceQuery): List<TorrentIndexRecord>
    suspend fun find(query: SourceQuery, id: String, hash: String): TorrentIndexRecord? =
        search(query).firstOrNull { it.id == id && it.hash == hash }
}

/** Search index metadata; availability/rights are not established by an index entry. */
class TpbTorrentIndex(
    private val endpoint: String = "https://apibay.org/",
    http: OkHttpClient = SourceHttp.client,
) : TorrentIndex {
    @Volatile private var retryAt = 0L
    private val http = http.newBuilder().addInterceptor { chain ->
        if (System.currentTimeMillis() < retryAt) throw java.io.IOException("الفهرس يحد الطلبات مؤقتاً؛ أعد المحاولة لاحقاً")
        val response = chain.proceed(chain.request())
        if (response.code == 429) {
            val header = response.header("Retry-After")
            val seconds = header?.toLongOrNull() ?: runCatching {
                (java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000
            }.getOrDefault(60)
            retryAt = System.currentTimeMillis() + seconds.coerceIn(1, 86400) * 1000
            response.close()
            throw java.io.IOException("الفهرس يحد الطلبات مؤقتاً؛ أعد المحاولة لاحقاً")
        }
        response
    }.build()
    override suspend fun search(query: SourceQuery): List<TorrentIndexRecord> = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext emptyList()
        val suffix = if (query.type == MediaType.TV) "S${query.season.toString().padStart(2, '0')}E${query.episode.toString().padStart(2, '0')}" else query.year.toString()
        val url = endpoint.toHttpUrl().newBuilder().addPathSegment("q.php")
            .addQueryParameter("q", "${query.originalTitle} $suffix").addQueryParameter("cat", "200").build()
        parse(SourceHttp.read(http, url.toString(), 2 * 1024 * 1024), query)
    }


    companion object {
        fun parse(payload: String, query: SourceQuery): List<TorrentIndexRecord> {
            if (!query.valid) return emptyList()
            val array = try { Json.parseToJsonElement(payload) as? JsonArray }
                catch (_: Exception) { null } ?: throw SourceException("فهرس التورنت أعاد بيانات غير صالحة")
            val expectedImdb = query.imdbId?.removePrefix("tt")?.takeIf { it.isNotBlank() }
            val title = SourceQuery.titleKey(query.originalTitle)
            return array.mapNotNull { value ->
                val row = value as? JsonObject ?: return@mapNotNull null
                fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val hash = text("info_hash").lowercase(Locale.ROOT)
                val name = text("name")
                val normalized = SourceQuery.titleKey(name)
                val imdb = text("imdb").removePrefix("tt").takeIf { Regex("[0-9]+").matches(it) && it.any { c -> c != '0' } }
                val category = text("category").toIntOrNull()
                if (!Regex("[a-f0-9]{40}").matches(hash) || hash.all { it == '0' }) return@mapNotNull null
                if (category != null && category !in 200..299) return@mapNotNull null
                if (query.type == MediaType.MOVIE && category != null && category !in setOf(201, 202, 207, 209, 211)) return@mapNotNull null
                if (expectedImdb != null && imdb != null && expectedImdb != imdb) return@mapNotNull null
                if (!normalized.startsWith("$title ")) return@mapNotNull null
                val suffix = normalized.removePrefix("$title ")
                if (query.type == MediaType.MOVIE) {
                    // A subtitle or episode of a similarly named series is not the requested film.
                    if (Regex("(?i)(?:s[0-9]+e[0-9]+|[0-9]+x[0-9]+)").containsMatchIn(name)) return@mapNotNull null
                    val year = Regex("(?<![0-9])(?:18|19|20|21)[0-9]{2}(?![0-9])").find(suffix)?.value?.toIntOrNull()
                    if (year != query.year || suffix.substringBefore(' ') != query.year.toString()) return@mapNotNull null
                } else {
                    val markers = Regex("(?i)(?:s([0-9]{1,3})e([0-9]{1,4})(?![0-9])|(?<![0-9])([0-9]{1,3})x([0-9]{1,4})(?![0-9]))").findAll(name).toList()
                    if (markers.size != 1) return@mapNotNull null
                    val marker = markers.single()
                    val season = (marker.groupValues[1].ifEmpty { marker.groupValues[3] }).toIntOrNull()
                    val episode = (marker.groupValues[2].ifEmpty { marker.groupValues[4] }).toIntOrNull()
                    if (season != query.season || episode != query.episode) return@mapNotNull null
                    // Reject combined episodes; the player has no per-episode boundary information.
                    if (Regex("(?i)^(?:[-_. ]?e[0-9]|[-_][0-9]{1,3}(?![0-9pP]))").containsMatchIn(name.substring(marker.range.last + 1))) return@mapNotNull null
                    if (expectedImdb == null || imdb == null) {
                        if (!Regex("(?i)^(?:${query.year}(?: |$)|s[0-9]+e[0-9]+(?: |$)|[0-9]+x[0-9]+(?: |$))").containsMatchIn(suffix)) return@mapNotNull null
                        val year = Regex("(?<![0-9])(?:18|19|20|21)[0-9]{2}(?![0-9])").find(suffix)?.value?.toIntOrNull()
                        if (year != query.year) return@mapNotNull null
                    }
                }
                val seeders = text("seeders").toIntOrNull()?.coerceAtLeast(0) ?: 0
                if (seeders == 0) return@mapNotNull null
                TorrentIndexRecord(hash, name, imdb?.let { "tt$it" }.orEmpty(), seeders,
                    text("id"), text("size").toLongOrNull()?.coerceAtLeast(0) ?: 0)
            }.distinctBy { it.hash }.sortedByDescending { it.seeders }
        }
    }
}

/** Direct index search: no pre-populated catalogue is required. */
class TpbSourceProvider(private val index: TorrentIndex = TpbTorrentIndex()) : WatchSourceProvider {
    override suspend fun search(query: SourceQuery): List<WatchSource> = index.search(query).take(20).mapNotNull { source(it, query) }
    override suspend fun find(query: SourceQuery, id: String): WatchSource? {
        val parts = id.split(':')
        if (parts.size != 2 || !Regex("[1-9][0-9]*").matches(parts[0])) return null
        return index.find(query, parts[0], parts[1])?.let { source(it, query) }
    }

    private fun source(record: TorrentIndexRecord, query: SourceQuery): WatchSource? {
        if (!Regex("[1-9][0-9]*").matches(record.id)) return null
        val magnet = "magnet:?xt=urn:btih:${record.hash}&dn=" + URLEncoder.encode(record.title, "UTF-8") +
            "&tr=" + URLEncoder.encode("udp://tracker.opentrackr.org:1337/announce", "UTF-8")
        val size = if (record.size > 0) String.format(Locale.US, "%.2f GB", record.size / 1_000_000_000.0) else "حجم غير معروف"
        return WatchSource("${record.id}:${record.hash}", "tpb", "The Pirate Bay", query,
            listOf(SourceVariant("magnet", record.title, magnet, SourceTransport.MAGNET)),
            attribution = "${record.seeders} أقران حسب الفهرس · $size",
            licenceUrl = "https://thepiratebay.org/description.php?id=${record.id}")
    }
}
