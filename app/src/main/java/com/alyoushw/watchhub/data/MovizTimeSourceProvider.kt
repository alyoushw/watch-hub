package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Resolve the site's public episode servers afresh; session URLs/cookies stay in memory. */
class MovizTimeSourceProvider : WatchSourceProvider {
    override suspend fun search(query: SourceQuery): List<WatchSource> = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext emptyList()
        val titles = listOfNotNull(query.searchTitle, query.originalTitle).filter { it.isNotBlank() }.distinctBy { SourceQuery.titleKey(it) }
        for (title in titles.take(2)) {
            for (page in 1..2) {
                val url = BASE.toHttpUrl().newBuilder().addQueryParameter("s", title)
                    .apply { if (page > 1) addQueryParameter("paged", page.toString()) }.build()
                val doc = read(url.toString()).doc
                val candidates = doc.select("h2.title-2 a[href]").filter { link ->
                    val urlValue = link.absUrl("href").toHttpUrlOrNull()
                    val series = urlValue?.encodedPath?.startsWith("/series/") == true
                    val season = seasonNumber(link.text().substringAfter("الموسم", ""))
                    urlValue?.host == HOST && series == (query.type == MediaType.TV) &&
                        (query.type != MediaType.TV || season == null || season == query.season) &&
                        (query.type != MediaType.MOVIE || query.year.toString() in link.text())
                }
                for (candidate in candidates.take(5)) {
                    val source = safelyResolve(read(candidate.absUrl("href")).doc, query, titles)
                    if (source != null) return@withContext listOf(source)
                }
                if (doc.select("h2.title-2 a[href]").isEmpty() || doc.select("a.next, .posts-navigation .next").isEmpty()) break
            }
        }
        emptyList()
    }

    override suspend fun find(query: SourceQuery, id: String): WatchSource? = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext null
        val post = Regex("moviz-(\\d{1,10})").matchEntire(id)?.groupValues?.get(1) ?: return@withContext null
        safelyResolve(read("$BASE?p=$post").doc, query, listOfNotNull(query.originalTitle, query.searchTitle))
    }

    private fun safelyResolve(doc: Document, query: SourceQuery, titles: List<String>): WatchSource? = try {
        resolvePage(doc, query, titles)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { null }

    private fun resolvePage(doc: Document, query: SourceQuery, titles: List<String>): WatchSource? {
        val fields = doc.select("p.movie_details_section").associate { row ->
            arabic(row.selectFirst(".ttl")?.text().orEmpty().trim().trimEnd(':')) to row.selectFirst(".cntt")?.text().orEmpty()
        }
        val series = query.type == MediaType.TV
        if (series != doc.select(".type-series").isNotEmpty()) return null
        val year = Regex("\\d{4}").find(arabic(fields["سنه الانتاج"].orEmpty()))?.value?.toIntOrNull()
        if (year != query.year) return null
        val name = fields[if (series) "اسم المسلسل" else "اسم الفيلم"].orEmpty()
            .removePrefix("فيلم ").removePrefix("مسلسل ").substringBefore("مترجم").substringBefore("مدبلج")
            .trim().removeSuffix(query.year.toString()).trim().trim('(', ')', '[', ']', ' ')
        if (titles.none { SourceQuery.titleKey(it) == SourceQuery.titleKey(name) }) return null
        if (series && seasonNumber(fields["الموسم"].orEmpty()) != query.season) return null
        val claimedImdb = doc.select("a[href*=imdb.com/title/]").mapNotNull {
            Regex("tt\\d{7,10}").find(it.attr("href"))?.value
        }
        if (!query.imdbId.isNullOrBlank() && claimedImdb.isNotEmpty() && query.imdbId !in claimedImdb) return null
        val post = doc.selectFirst("link[rel=shortlink]")?.absUrl("href")?.toHttpUrlOrNull()?.queryParameter("p")
            ?: Regex("(?:postid-|post-)(\\d+)").find(doc.body().className() + " " + doc.select(".postWrap").html())?.groupValues?.get(1)
            ?: return null
        if (!post.matches(Regex("\\d{1,10}"))) return null
        val pageUrl = doc.location().toHttpUrlOrNull()?.takeIf { it.host == HOST }?.toString() ?: return null
        val embeds = if (series) doc.select(".servers-block .ep-item").filter {
            Regex("الحلقه\\s*(\\d+)").find(arabic(it.text()))?.groupValues?.get(1)?.toIntOrNull() == query.episode
        }.mapNotNull {
            Regex("iframe_area\\.location\\.href\\s*=\\s*['\"]([^'\"]+)['\"]").find(it.attr("onclick"))?.groupValues?.get(1)
        } else doc.select("#servers_tabs iframe").map { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
        val variants = embeds.distinct().take(4).mapIndexedNotNull { index, embed ->
            try { resolveEmbed(embed, pageUrl, index) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        if (variants.isEmpty()) return null
        return WatchSource("moviz-$post", "moviz-time", "Moviz Time", query, variants,
            attribution = "Moviz Time · ${query.originalTitle} (${query.year}) · $pageUrl", licenceUrl = pageUrl)
    }

    private fun resolveEmbed(embed: String, pageUrl: String, index: Int): SourceVariant? {
        val url = embed.toHttpUrlOrNull()?.takeIf {
            it.host == "vidhls.com" && it.encodedPath == "/player/index.php" &&
                it.queryParameter("data")?.matches(Regex("[a-fA-F0-9]{32}")) == true
        } ?: return null
        val page = read(url.toString(), pageUrl)
        val config = Regex("FirePlayer\\(vhash,\\s*(\\{.*?\\}),\\s*(?:false|true)\\)", RegexOption.DOT_MATCHES_ALL)
            .find(page.doc.html())?.groupValues?.get(1) ?: return null
        val data = Json.parseToJsonElement(config) as? JsonObject ?: return null
        val path = (data["videoUrl"] as? JsonPrimitive)?.contentOrNull ?: return null
        val server = (data["videoServer"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (!Regex("/cdn/hls/[a-zA-Z0-9_-]+/master\\.txt").matches(path) || !server.matches(Regex("[0-9]{1,3}"))) return null
        // The site's own player uses this proxy route, not the hostList CDN addresses.
        val manifest = "https://vidhls.com".toHttpUrl().newBuilder().encodedPath(path).addQueryParameter("s", server).build()
        val cookie = page.cookies.filter { it.name == "PHPSESSID" && it.matches(manifest) }.joinToString("; ") { "${it.name}=${it.value}" }
        if (cookie.isBlank()) return null
        val headers = mapOf("User-Agent" to AGENT, "Referer" to url.toString(), "Cookie" to cookie,
            "Accept" to "*/*", "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty")
        return SourceVariant("server-${index + 1}", "السيرفر ${index + 1} · HLS", manifest.toString(), SourceTransport.HLS, headers)
    }

    private data class Page(val doc: Document, val cookies: List<Cookie>)
    private fun read(url: String, referer: String = BASE): Page {
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host in setOf(HOST, "vidhls.com") }
            ?: throw SourceException("رابط Moviz Time غير متوافق")
        val request = Request.Builder().url(parsed).header("User-Agent", AGENT).header("Referer", referer).build()
        return SourceHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SourceException("تعذر الوصول إلى Moviz Time (${response.code})")
            if (response.request.url.host !in setOf(HOST, "vidhls.com")) throw SourceException("تحويل غير متوافق من Moviz Time")
            val body = response.body ?: throw SourceException("صفحة Moviz Time فارغة")
            val input = body.source()
            if (input.request(2 * 1024 * 1024L + 1)) throw SourceException("صفحة Moviz Time أكبر من الحد المسموح")
            Page(Jsoup.parse(input.readByteArray().toString(Charsets.UTF_8), response.request.url.toString()), Cookie.parseAll(parsed, response.headers))
        }
    }

    private fun arabic(text: String): String = text.map { char -> when (char) {
        'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ى' -> 'ي'
        in '٠'..'٩' -> '0' + (char - '٠'); in '۰'..'۹' -> '0' + (char - '۰'); else -> char
    } }.joinToString("").trim()

    private fun seasonNumber(text: String): Int? {
        val value = arabic(text).replace("الموسم", "").trim()
        return Regex("^\\d+$").matchEntire(value)?.value?.toIntOrNull() ?: ORDINALS.indexOf(value).takeIf { it >= 0 }?.plus(1)
    }

    companion object {
        private const val HOST = "moviz-time.buzz"
        private const val BASE = "https://moviz-time.buzz/"
        private const val AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val ORDINALS = listOf("الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر",
            "الحادي عشر", "الثاني عشر", "الثالث عشر", "الرابع عشر", "الخامس عشر", "السادس عشر", "السابع عشر", "الثامن عشر", "التاسع عشر", "العشرون",
            "الحادي والعشرون", "الثاني والعشرون", "الثالث والعشرون", "الرابع والعشرون", "الخامس والعشرون", "السادس والعشرون", "السابع والعشرون", "الثامن والعشرون", "التاسع والعشرون", "الثلاثون")
    }
}
