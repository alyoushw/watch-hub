package com.alyoushw.watchhub.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val probe = StreamProbe(verifyHlsSegments = true)

    override suspend fun search(query: SourceQuery): List<WatchSource> = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext emptyList()
        val titles = aliases(query)
        val searches = titles.take(3).flatMap { title ->
            if (query.type == MediaType.TV) listOf("$title الموسم ${ORDINALS.getOrNull((query.season ?: 1) - 1) ?: query.season} الحلقة ${query.episode}", title)
            else listOf(title)
        }.distinct()
        val visited = mutableSetOf<String>()
        var failed: Exception? = null
        for (search in searches) {
            var next = BASE.toHttpUrl().newBuilder().addQueryParameter("s", search)
                .addQueryParameter("type", if (query.type == MediaType.TV) "series" else "movies").build().toString()
            for (page in 1..4) {
                val doc = try { read(next) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed = e; break }
                val candidates = doc.select("a.block[href]").filter {
                    it.absUrl("href").toHttpUrlOrNull()?.host == HOST &&
                        titleMatches(it.selectFirst("h3.title")?.text().orEmpty(), query, titles, false)
                }.distinctBy { it.absUrl("href") }
                for (candidate in candidates) {
                    if (!visited.add(candidate.absUrl("href"))) continue
                    val details = try { read(candidate.absUrl("href")) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failed = e; continue }
                    try { safelyResolve(details, query, titles)?.let { return@withContext listOf(it) } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed = e }
                    if (query.type == MediaType.TV) {
                        val episodes = details.select("a.block[href]").filter {
                            it.absUrl("href").toHttpUrlOrNull()?.host == HOST &&
                                titleMatches(it.selectFirst("h3.title")?.text().orEmpty(), query, titles, true)
                        }
                        for (episode in episodes) {
                            if (!visited.add(episode.absUrl("href"))) continue
                            try {
                                safelyResolve(read(episode.absUrl("href")), query, titles)?.let { return@withContext listOf(it) }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { failed = e }
                        }
                    }
                }
                next = doc.select("a.next, a.next-page, a.next.page-numbers, a[rel=next]")
                    .map { it.absUrl("href") }.firstOrNull { it.toHttpUrlOrNull()?.host == HOST } ?: break
            }
        }
        failed?.let { throw it }
        emptyList()
    }

    override suspend fun find(query: SourceQuery, id: String): WatchSource? = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext null
        val post = Regex("egybest-(\\d{1,10})").matchEntire(id)?.groupValues?.get(1) ?: return@withContext null
        safelyResolve(read("$BASE?p=$post"), query, aliases(query))
    }

    override suspend fun findVariant(query: SourceQuery, id: String, variantId: String): WatchSource? = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext null
        val post = Regex("egybest-(\\d{1,10})").matchEntire(id)?.groupValues?.get(1) ?: return@withContext null
        resolve(read("$BASE?p=$post"), query, aliases(query), variantId)
    }

    private suspend fun safelyResolve(doc: Document, query: SourceQuery, titles: List<String>): WatchSource? =
        resolve(doc, query, titles)

    private suspend fun resolve(doc: Document, query: SourceQuery, titles: List<String>, targetId: String? = null): WatchSource? {
        val page = doc.location().toHttpUrlOrNull()?.takeIf { it.host == HOST } ?: return null
        val title = doc.selectFirst("h1.main-title")?.text() ?: return null
        if (!titleMatches(title, query, titles, true)) return null
        val imdb = doc.select("a[href*=imdb.com/title/]").mapNotNull { Regex("tt\\d{7,10}").find(it.attr("href"))?.value }
        if (!query.imdbId.isNullOrBlank() && imdb.isNotEmpty() && query.imdbId !in imdb) return null
        // A later season's air year differs from the series debut year in TMDB.
        // Compare against TMDB's selected season year, never the WordPress publication date.
        val years = Regex("\\b(?:18|19|20|21)\\d{2}\\b").findAll(arabic(title)).mapNotNull { it.value.toIntOrNull() }.toList()
        val yearMatches = query.year in years || query.type == MediaType.TV && query.seasonYear != null && query.seasonYear in years
        if (!yearMatches && (query.imdbId.isNullOrBlank() || query.imdbId !in imdb)) return null
        val post = doc.selectFirst("link[rel=shortlink]")?.absUrl("href")?.toHttpUrlOrNull()?.queryParameter("p")
            ?.takeIf { it.matches(Regex("\\d{1,10}")) } ?: return null
        val servers = doc.select("#WatchServers [data-embed-url]").mapNotNull {
            val url = it.absUrl("data-embed-url").toHttpUrlOrNull()?.takeIf { parsed -> parsed.isHttps } ?: return@mapNotNull null
            SourceWebLink(it.text().ifBlank { url.host }, url.toString())
        }.distinctBy { it.url }
        val downloads = doc.select(".download-section .tr, .downloads .tr").mapNotNull { row ->
            val url = row.selectFirst("a[href]")?.absUrl("href")?.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@mapNotNull null
            val label = row.children().take(2).joinToString(" · ") { it.text() }.ifBlank { url.host }
            SourceWebLink(label, url.toString(), download = true)
        }.distinctBy { it.url }
        val limit = Semaphore(4)
        val issues = java.util.Collections.synchronizedList(mutableListOf<SourceIssue>())
        val variants = coroutineScope {
            servers.withIndex().filter { (index, server) ->
                server.url.toHttpUrlOrNull()?.host in EMBED_HOSTS &&
                    (targetId == null || targetId.startsWith("egy-${serverKey(server.url)}-") || targetId.startsWith("server-$index-"))
            }.map { (index, server) -> async {
                limit.withPermit {
                    try {
                        val media = embeds.resolveEmbed(server.url, page.toString(), server.label, index.toString(), EMBED_HOSTS)
                        if (media.isEmpty()) issues += SourceIssue(server.label, SourceHealth.UNVERIFIED)
                        media
                            .map { variant -> variant.copy(id = if (targetId?.startsWith("server-") == true) variant.id
                                else "egy-${serverKey(server.url)}-${variantKey(variant)}") }
                            .filter { targetId == null || it.id == targetId }
                            .filter { variant ->
                                val check = probe.inspect(variant)
                                if (!check.playable) issues += check.issue(server.label)
                                check.playable
                            }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { issues += sourceFailure(server.label, e); emptyList() }
                }
            } }.awaitAll().flatten().distinctBy { it.url }
        }
        return WatchSource("egybest-$post", "egybest", "EgyBest", query, variants,
            attribution = "EgyBest · ${query.originalTitle} (${query.year}) · $page", licenceUrl = page.toString(),
            webLinks = servers + downloads + SourceWebLink("صفحة العمل الأصلية", page.toString()), issues = issues.toList().distinct())
    }

    private fun titleMatches(raw: String, query: SourceQuery, titles: List<String>, requireEpisode: Boolean): Boolean {
        var text = arabic(raw).removePrefix("مشاهده ")
        val series = query.type == MediaType.TV
        if (series && !text.startsWith("مسلسل ") && !text.startsWith("انمي ") && !text.startsWith("كرتون ")) return false
        if (!series && !text.startsWith("فيلم ")) return false
        text = text.substringAfter(' ')
        if (series) {
            val season = Regex("الموسم\\s+(.+?)(?:\\s+الحلقه|\\s+مترجم|\\s+مدبلج|$)").find(text)
            val value = season?.groupValues?.get(1)?.trim()?.let { it.toIntOrNull() ?: ORDINALS.indexOf(it).takeIf { i -> i >= 0 }?.plus(1) } ?: 1
            if (value != query.season) return false
            val episode = Regex("الحلقه\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            if ((requireEpisode || episode != null) && episode != query.episode) return false
            text = text.substringBefore("الموسم").substringBefore("الحلقه").trim()
        } else {
            text = text.substringBefore("مترجم").substringBefore("مدبلج").substringBefore("اونلاين").trim()
                .removeSuffix(query.year.toString()).trim().trim('(', ')', ' ')
        }
        return titles.any { SourceQuery.titleKey(arabic(it)) == SourceQuery.titleKey(text) }
    }

    private fun aliases(query: SourceQuery) = query.websiteTitles()

    /** Host order can change on the website; source identity must follow the embed, not its row. */
    private fun serverKey(url: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun variantKey(variant: SourceVariant): String =
        Regex("/(hls[234]?)(?:/|_)").find(variant.url.toHttpUrlOrNull()?.encodedPath.orEmpty())?.groupValues?.get(1)
            ?: "${variant.transport.name.lowercase(java.util.Locale.ROOT)}-${variant.id.substringAfterLast('-')}"

    private fun read(url: String): Document {
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == HOST } ?: throw SourceException("رابط EgyBest غير متوافق")
        return SourceHttp.client.newCall(Request.Builder().url(parsed).header("User-Agent", AGENT).build()).execute().use { response ->
            if (!response.isSuccessful || response.request.url.host != HOST) throw SourceException("تعذر الوصول إلى EgyBest (${response.code})", response.code)
            val source = response.body?.source() ?: throw SourceException("صفحة EgyBest فارغة")
            if (source.request(2 * 1024 * 1024L + 1)) throw SourceException("صفحة EgyBest أكبر من الحد المسموح")
            checkedSourcePage(Jsoup.parse(source.readByteArray().toString(Charsets.UTF_8), response.request.url.toString()))
        }
    }

    private fun arabic(value: String) = value.map { char -> when (char) {
        'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ى' -> 'ي'; in '٠'..'٩' -> '0' + (char - '٠'); in '۰'..'۹' -> '0' + (char - '۰'); else -> char
    } }.joinToString("").trim()

    companion object {
        private const val HOST = "egytbest.live"
        private const val BASE = "https://egytbest.live/"
        private const val AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val EMBED_HOSTS = setOf("uqload.vc", "uqload.net", "www.mp4upload.com", "mp4upload.com", "vidspeed.org", "vidmoly.org", "1vid.xyz", "mp4plus.org",
            "forafile.com", "smoothpre.com", "minochinos.com", "stmruby.com", "streamruby.com", "hgcloud.to", "playmogo.com", "mixdrop.top", "mixdrop.ps", "voe.sx")
        private val ORDINALS = listOf("الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر",
            "الحادي عشر", "الثاني عشر", "الثالث عشر", "الرابع عشر", "الخامس عشر", "السادس عشر", "السابع عشر", "الثامن عشر", "التاسع عشر", "العشرون")
    }
}
