package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A person's article: its text and where it comes from. */
data class WikiBio(val text: String, val url: String, val arabic: Boolean)

/**
 * Biographies from Wikipedia (Arabic first, then English) for actors TMDB knows little about —
 * many Arab actors have no TMDB biography at all.
 */
object Wikipedia {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** The article for the first of [names] Wikipedia has, as plain text (whole article, trimmed). */
    suspend fun bio(names: List<String>, english: List<String>): WikiBio? = withContext(Dispatchers.IO) {
        for (n in names.filter { it.isNotBlank() }.distinct()) fetch("ar", n)?.let { return@withContext it }
        for (n in english.filter { it.isNotBlank() }.distinct()) fetch("en", n)?.let { return@withContext it }
        null
    }

    private fun fetch(lang: String, title: String): WikiBio? = runCatching {
        val url = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "extracts|pageprops")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("titles", title)
            .build()
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", "WatchHub/1.0 (Android app)").build()).execute().use { it.body?.string().orEmpty() }
        val pages = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject ?: return null
        val page = pages.values.firstOrNull()?.jsonObject ?: return null
        // Disambiguation pages ("may refer to") are not about this person.
        if (page["pageprops"]?.jsonObject?.containsKey("disambiguation") == true) return null
        val text = page["extract"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.length < 80) return null
        val name = page["title"]?.jsonPrimitive?.contentOrNull ?: title
        WikiBio(
            text = text.replace(Regex("\n{3,}"), "\n\n").take(6000),
            url = "https://$lang.wikipedia.org/wiki/" + java.net.URLEncoder.encode(name.replace(' ', '_'), "UTF-8"),
            arabic = lang == "ar",
        )
    }.getOrNull()
}
