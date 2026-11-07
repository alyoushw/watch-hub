package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Finds the title's own page on each streaming service (e.g. netflix.com/title/80057281),
 * so "watch" opens the title itself instead of a search.
 *
 * TMDB does not expose service-specific ids, but Wikidata links TMDB ids to Netflix,
 * Prime Video and Crunchyroll ids. Rather than hard-coding Wikidata property numbers,
 * the query takes every identifier on the item whose URL template (P1630) points at
 * one of the services, so new or renamed properties keep working.
 */
class DirectLinks {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Map<StreamingService, String>>()

    /** Service → direct URL. Empty when nothing is known; never throws. */
    suspend fun resolve(type: MediaType, tmdbId: Int): Map<StreamingService, String> {
        val key = WatchItem.keyOf(type, tmdbId)
        cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { query(type, tmdbId) }
                .onSuccess { cache[key] = it }
                .getOrDefault(emptyMap())
        }
    }

    private fun query(type: MediaType, tmdbId: Int): Map<StreamingService, String> {
        // P4947 = TMDB movie ID, P4983 = TMDB TV series ID.
        val tmdbProperty = if (type == MediaType.TV) "P4983" else "P4947"
        val sparql = """
            SELECT ?formatter ?value WHERE {
              ?item wdt:$tmdbProperty "$tmdbId" .
              ?item ?claim ?value .
              ?property wikibase:directClaim ?claim ;
                        wdt:P1630 ?formatter .
              FILTER(REGEX(STR(?formatter), "netflix\\.com/|primevideo\\.com/|amazon\\.[a-z.]+/gp/video/|crunchyroll\\.com/"))
            }
        """.trimIndent()

        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("query", sparql)
            .addQueryParameter("format", "json")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/sparql-results+json")
            // Wikidata asks every client to identify itself.
            .header("User-Agent", "WatchHub/0.2 (Android personal streaming hub)")
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Wikidata ${response.code}")
            response.body?.string().orEmpty()
        }

        val bindings = json.parseToJsonElement(body)
            .jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray
            ?: return emptyMap()

        val links = LinkedHashMap<StreamingService, String>()
        for (binding in bindings) {
            val row = binding.jsonObject
            val formatter = row["formatter"]?.jsonObject?.get("value")?.jsonPrimitive?.content ?: continue
            val value = row["value"]?.jsonObject?.get("value")?.jsonPrimitive?.content ?: continue
            val service = when {
                "netflix" in formatter -> StreamingService.NETFLIX
                "crunchyroll" in formatter -> StreamingService.CRUNCHYROLL
                else -> StreamingService.PRIME
            }
            if (service !in links && "$1" in formatter) {
                links[service] = formatter.replace("$1", value)
            }
        }
        return links
    }

    private companion object {
        const val ENDPOINT = "https://query.wikidata.org/sparql"
    }
}
