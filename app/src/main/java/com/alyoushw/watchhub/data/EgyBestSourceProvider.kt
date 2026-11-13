package com.alyoushw.watchhub.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Resolve public episode servers afresh; never persist session URLs as source identifiers. */
class EgyBestSourceProvider : WatchSourceProvider {
    private val embeds = TopCinemaSourceProvider()
    private val probe = StreamProbe()

    override suspend fun search(query: SourceQuery): List<WatchSource> = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext emptyList()
        val titles = aliases(query)
        for (title in titles.take(2)) {
            for (page in 1..2) {
                val search = if (query.type == MediaType.TV) "$title الموسم ${ORDINALS.getOrNull((query.season ?: 1) - 1) ?: query.season} الحلقة ${query.episode}" else title
                val url = BASE.toHttpUrl().newBuilder().addQueryParameter("s", search)
                    .addQueryParameter("type", if (query.type == MediaType.TV) "series" else "movies")
                    .apply { if (page > 1) addQueryParameter("paged", page.toString()) }.build()
                val doc = read(url.toString())
                val candidates = doc.select("a.block[href]").filter {
                    it.absUrl("href").toHttpUrlOrNull()?.host == HOST &&
                        titleMatches(it.selectFirst("h3.title")?.text().orEmpty(), query, titles, false)
                }.distinctBy { it.absUrl("href") }
                for (candidate in candidates.take(3)) {
                    val details = read(candidate.absUrl("href"))
                    val source = safelyResolve(details, query, titles)
                    if (source != null) return@withContext listOf(source)
                    // Season pages contain links to the actual individual episode pages.
                    if (query.type == MediaType.TV) {
                        val episode = details.select("a.block[href]").firstOrNull {
                            it.absUrl("href").toHttpUrlOrNull()?.host == HOST &&
                                titleMatches(it.selectFirst("h3.title")?.text().orEmpty(), query, titles, true)
                        }
                        if (episode != null) {
                            safelyResolve(read(episode.absUrl("href")), query, titles)?.let { return@withContext listOf(it) }
                        }
                    }
                }
                if (doc.select("a.block[href]").isEmpty() || doc.select("a.next, a.next-page, .pagination a").isEmpty()) break
            }
        }
        emptyList()
    }

    override suspend fun find(query: SourceQuery, id: String): WatchSource? = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext null
        val post = Regex("egybest-(\\d{1,10})").matchEntire(id)?.groupValues?.get(1) ?: return@withContext null
        safelyResolve(read("$BASE?p=$post"), query, aliases(query))
    }

    private suspend fun safelyResolve(doc: Document, query: SourceQuery, titles: List<String>): WatchSource? = try {
        resolve(doc, query, titles)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    private suspend fun resolve(doc: Document, query: SourceQuery, titles: List<String>): WatchSource? {
        val page = doc.location().toHttpUrlOrNull()?.takeIf { it.host == HOST } ?: return null
        val title = doc.selectFirst("h1.main-title")?.text() ?: return null
        if (!titleMatches(title, query, titles, true)) return null
        val imdb = doc.select("a[href*=imdb.com/title/]").mapNotNull { Regex("tt\\d{7,10}").find(it.attr("href"))?.value }
        if (!query.imdbId.isNullOrBlank() && imdb.isNotEmpty() && query.imdbId !in imdb) return null
        // Never treat the WordPress publication date as the production/debut year.
        // If an episode advertises only its season year, omit it unless IMDb confirms identity.
        val years = Regex("\\b(?:18|19|20|21)\\d{2}\\b").findAll(arabic(title)).mapNotNull { it.value.toIntOrNull() }.toList()
        if (query.year !in years && (query.imdbId.isNullOrBlank() || query.imdbId !in imdb)) return null
        val post = doc.selectFirst("link[rel=shortlink]")?.absUrl("href")?.toHttpUrlOrNull()?.queryParameter("p")
            ?.takeIf { it.matches(Regex("\\d{1,10}")) } ?: return null
        val servers = doc.select("#WatchServers [data-embed-url]").mapNotNull {
            val url = it.attr("data-embed-url").toHttpUrlOrNull()?.takeIf { parsed -> parsed.isHttps && parsed.host in EMBED_HOSTS }
            url?.toString()?.let { value -> value to it.text() }
        }.distinctBy { it.first }.sortedBy { if ("mp4upload" in it.first) 0 else 1 }
        val variants = mutableListOf<SourceVariant>()
        for ((index, server) in servers.take(4).withIndex()) {
            try {
                val media = embeds.resolveEmbed(server.first, page.toString(), server.second, index.toString(), EMBED_HOSTS)
                for (variant in media) {
                    if (probe.compatible(variant)) { variants += variant; break }
                }
                if (variants.size >= 2) break
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Deleted or blocked servers must not hide a working alternative. */ }
        }
        if (variants.isEmpty()) return null
        return WatchSource("egybest-$post", "egybest", "EgyBest", query, variants,
            attribution = "EgyBest · ${query.originalTitle} (${query.year}) · $page", licenceUrl = page.toString())
    }

    private fun titleMatches(raw: String, query: SourceQuery, titles: List<String>, requireEpisode: Boolean): Boolean {
        var text = arabic(raw).removePrefix("مشاهده ")
        val series = query.type == MediaType.TV
        if (series && !text.startsWith("مسلسل ") && !text.startsWith("انمي ") && !text.startsWith("كرتون ")) return false
        if (!series && !text.startsWith("فيلم ")) return false
        text = text.substringAfter(' ')
        if (series) {
            val season = Regex("الموسم\\s+(.+?)(?:\\s+الحلقه|\\s+مترجم|\\s+مدبلج|$)").find(text) ?: return false
            val value = season.groupValues[1].trim().let { it.toIntOrNull() ?: ORDINALS.indexOf(it).takeIf { i -> i >= 0 }?.plus(1) }
            if (value != query.season) return false
            val episode = Regex("الحلقه\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            if ((requireEpisode || episode != null) && episode != query.episode) return false
            text = text.substringBefore("الموسم").trim()
        } else {
            text = text.substringBefore("مترجم").substringBefore("مدبلج").substringBefore("اونلاين").trim()
                .removeSuffix(query.year.toString()).trim().trim('(', ')', ' ')
        }
        return titles.any { SourceQuery.titleKey(arabic(it)) == SourceQuery.titleKey(text) }
    }

    private fun aliases(query: SourceQuery) = listOfNotNull(query.searchTitle, query.originalTitle)
        .filter { it.isNotBlank() }.distinctBy { SourceQuery.titleKey(it) }

    private fun read(url: String): Document {
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == HOST } ?: throw SourceException("رابط EgyBest غير متوافق")
        return SourceHttp.client.newCall(Request.Builder().url(parsed).header("User-Agent", AGENT).build()).execute().use { response ->
            if (!response.isSuccessful || response.request.url.host != HOST) throw SourceException("تعذر الوصول إلى EgyBest (${response.code})")
            val source = response.body?.source() ?: throw SourceException("صفحة EgyBest فارغة")
            if (source.request(2 * 1024 * 1024L + 1)) throw SourceException("صفحة EgyBest أكبر من الحد المسموح")
            Jsoup.parse(source.readByteArray().toString(Charsets.UTF_8), response.request.url.toString())
        }
    }

    private fun arabic(value: String) = value.map { char -> when (char) {
        'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ى' -> 'ي'; in '٠'..'٩' -> '0' + (char - '٠'); in '۰'..'۹' -> '0' + (char - '۰'); else -> char
    } }.joinToString("").trim()

    companion object {
        private const val HOST = "egytbest.live"
        private const val BASE = "https://egytbest.live/"
        private const val AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val EMBED_HOSTS = setOf("uqload.vc", "uqload.net", "www.mp4upload.com", "mp4upload.com", "vidspeed.org", "vidmoly.org", "1vid.xyz", "mp4plus.org")
        private val ORDINALS = listOf("الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر",
            "الحادي عشر", "الثاني عشر", "الثالث عشر", "الرابع عشر", "الخامس عشر", "السادس عشر", "السابع عشر", "الثامن عشر", "التاسع عشر", "العشرون")
    }
}
