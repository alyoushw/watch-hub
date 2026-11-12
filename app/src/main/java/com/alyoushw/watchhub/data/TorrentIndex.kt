package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

data class TorrentIndexRecord(val hash: String, val title: String, val imdbId: String, val seeders: Int)

interface TorrentIndex {
    suspend fun search(query: SourceQuery): List<TorrentIndexRecord>
}

/** Index metadata only. It never returns a playable URL or grants rights to indexed works. */
class TpbTorrentIndex : TorrentIndex {
    override suspend fun search(query: SourceQuery): List<TorrentIndexRecord> = withContext(Dispatchers.IO) {
        if (!query.valid || query.imdbId.isNullOrBlank()) return@withContext emptyList()
        val suffix = if (query.type == MediaType.TV) "S${query.season.toString().padStart(2, '0')}E${query.episode.toString().padStart(2, '0')}" else query.year.toString()
        val url = "https://apibay.org/q.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${query.originalTitle} $suffix").addQueryParameter("cat", "0").build()
        parse(SourceHttp.read(SourceHttp.client, url.toString(), 2 * 1024 * 1024), query)
    }

    companion object {
        fun parse(payload: String, query: SourceQuery): List<TorrentIndexRecord> {
            val array = Json.parseToJsonElement(payload) as? JsonArray ?: throw SourceException("فهرس التورنت أعاد بيانات غير صالحة")
            val imdb = query.imdbId?.removePrefix("tt") ?: return emptyList()
            return array.mapNotNull { value ->
                val row = value as? JsonObject ?: return@mapNotNull null
                fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val hash = text("info_hash").lowercase(java.util.Locale.ROOT)
                val name = text("name")
                val normalized = SourceQuery.titleKey(name)
                val title = SourceQuery.titleKey(query.originalTitle)
                if (!Regex("[a-f0-9]{40}").matches(hash) || hash.all { it == '0' } || text("imdb").removePrefix("tt") != imdb) return@mapNotNull null
                if (!normalized.startsWith("$title ")) return@mapNotNull null
                if (query.type == MediaType.MOVIE && !Regex("(?<![0-9])${query.year}(?![0-9])").containsMatchIn(name)) return@mapNotNull null
                if (query.type == MediaType.TV && !Regex("(?i)s0*${query.season}e0*${query.episode}(?![0-9])").containsMatchIn(name)) return@mapNotNull null
                TorrentIndexRecord(hash, name, "tt$imdb", text("seeders").toIntOrNull()?.coerceAtLeast(0) ?: 0)
            }
        }
    }
}

/** An index can enrich only hashes already declared by the owner's authorized catalogue. */
class IndexedCatalogueProvider(private val catalogue: WatchSourceProvider, private val index: TorrentIndex) : WatchSourceProvider {
    override suspend fun search(query: SourceQuery): List<WatchSource> {
        val sources = catalogue.search(query).filter { it.compatible(query) }
        val hashes = sources.flatMap { source -> source.variants.mapNotNull { magnetHash(it.url) } }.toSet()
        if (hashes.isEmpty()) return sources
        val matches = try { index.search(query).filter { it.hash in hashes }.associateBy { it.hash } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { return sources }
        return sources.map { source -> source.copy(variants = source.variants.map { variant ->
            val record = magnetHash(variant.url)?.let(matches::get)
            if (record == null) variant else variant.copy(label = "${variant.label} · ${record.seeders} أقران")
        }) }
    }
}
