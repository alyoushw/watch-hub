package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Discover direct files using exact film identity and the archive's declared licence. */
class ArchiveSourceProvider : WatchSourceProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SourceQuery): List<WatchSource> = withContext(Dispatchers.IO) {
        if (!query.valid || query.type != MediaType.MOVIE) return@withContext emptyList()
        val title = query.originalTitle.replace("\\", "\\\\").replace("\"", "\\\"")
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", "title:(\"$title\") AND year:${query.year} AND mediatype:movies AND licenseurl:(*creativecommons.org*)")
            .addQueryParameter("output", "json").addQueryParameter("rows", "12")
            .addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "year").addQueryParameter("fl[]", "licenseurl")
            .addQueryParameter("sort[]", "downloads desc").build()
        val root = readObject(url.toString())
        val docs = (root["response"] as? JsonObject)?.get("docs") as? JsonArray ?: return@withContext emptyList()
        val ids = docs.mapNotNull { it as? JsonObject }.filter { matches(it, query) && licence(it) != null }
            .mapNotNull { it["identifier"].values().firstOrNull() }.distinct().take(4)
        coroutineScope { ids.map { id -> async { safelyRead(id, query) } }.awaitAll().filterNotNull() }
    }

    override suspend fun find(query: SourceQuery, id: String): WatchSource? = withContext(Dispatchers.IO) {
        if (!query.valid || query.type != MediaType.MOVIE || !id.startsWith("ia:")) return@withContext null
        safelyRead(id.removePrefix("ia:"), query)
    }

    private fun safelyRead(id: String, query: SourceQuery): WatchSource? {
        if (!Regex("[a-zA-Z0-9_.-]{1,150}").matches(id)) return null
        return try { source(id, query) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private fun source(id: String, query: SourceQuery): WatchSource? {
        val root = readObject("https://archive.org/metadata/$id")
        if (root["is_dark"].values().firstOrNull() == "true") return null
        val meta = root["metadata"] as? JsonObject ?: return null
        if (!matches(meta, query) || meta["mediatype"].values().firstOrNull() != "movies") return null
        val licence = licence(meta) ?: return null
        val imdb = (meta["external-identifier"].values() + meta["imdb"].values() + meta["imdb_id"].values())
            .flatMap { Regex("tt\\d{7,10}").findAll(it).map { match -> match.value }.toList() }
        if (!query.imdbId.isNullOrBlank() && imdb.isNotEmpty() && query.imdbId !in imdb) return null
        val files = (root["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun fileUrl(name: String) = "https://archive.org/download/".toHttpUrl().newBuilder()
            .addPathSegment(id).addPathSegment(name).build().toString()
        val variants = files.mapNotNull { file ->
            val name = file["name"].values().firstOrNull() ?: return@mapNotNull null
            val duration = file["length"].values().firstOrNull()?.toDoubleOrNull() ?: return@mapNotNull null
            if (!name.endsWith(".mp4", true) || !duration.isFinite() || duration < 300 ||
                file["private"].values().firstOrNull() in listOf("true", "1") ||
                Regex("(?:^|[_. -])(trailer|sample|preview)(?:[_. -]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)) return@mapNotNull null
            val height = file["height"].values().firstOrNull()?.toIntOrNull()?.takeIf { it in 144..4320 }
            SourceVariant(name, if (height != null) "${height}p · MP4" else "MP4 · $name", fileUrl(name), SourceTransport.MP4)
        }.take(3)
        if (variants.isEmpty()) return null
        val subtitles = files.mapNotNull { file ->
            val name = file["name"].values().firstOrNull() ?: return@mapNotNull null
            val format = name.substringAfterLast('.').lowercase(java.util.Locale.ROOT)
            if (format !in setOf("srt", "vtt") || file["private"].values().firstOrNull() in listOf("true", "1")) return@mapNotNull null
            val declared = file["language"].values().firstOrNull()?.lowercase(java.util.Locale.ROOT)
            val language = when {
                declared in setOf("ar", "ara", "arabic") || Regex("[_. -](ar|ara|arabic)[_. -]", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "ar"
                declared in setOf("en", "eng", "english") || Regex("[_. -](en|eng|english)[_. -]", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "en"
                else -> return@mapNotNull null
            }
            SourceSubtitle(name, if (language == "ar") "العربية" else "English", language, fileUrl(name), format)
        }.take(4)
        return WatchSource("ia:$id", "internet-archive", "Internet Archive · بث مباشر", query, variants, subtitles,
            attribution = "${query.originalTitle} (${query.year}) · archive.org/details/$id · declared licence: $licence; retain original credits",
            licenceUrl = licence)
    }

    private fun matches(meta: JsonObject, query: SourceQuery): Boolean {
        val years = meta["year"].values().ifEmpty { meta["date"].values() }
            .mapNotNull { Regex("^\\d{4}").find(it)?.value?.toIntOrNull() }
        if (query.year !in years) return false
        return meta["title"].values().any { title ->
            val original = title.trim().removeSuffix(" (${query.year})").removeSuffix(" [${query.year}]").removeSuffix(" ${query.year}")
            SourceQuery.titleKey(original) == SourceQuery.titleKey(query.originalTitle)
        }
    }

    private fun licence(meta: JsonObject): String? = meta["licenseurl"].values().firstOrNull { value ->
        val url = value.toHttpUrlOrNull()
        url != null && url.host in setOf("creativecommons.org", "www.creativecommons.org") &&
            Regex("^/(licenses/(by(?:-nc)?(?:-sa|-nd)?|publicdomain)|publicdomain/(zero|mark))/").containsMatchIn(url.encodedPath)
    }

    private fun readObject(url: String): JsonObject =
        json.parseToJsonElement(SourceHttp.read(SourceHttp.client, url, 2 * 1024 * 1024)) as? JsonObject
            ?: throw SourceException("تعذر قراءة مصدر Internet Archive")

    private fun JsonElement?.values(): List<String> = when (this) {
        is JsonPrimitive -> listOfNotNull(contentOrNull)
        is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }
}
