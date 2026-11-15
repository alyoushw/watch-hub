package com.alyoushw.watchhub.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Public post, season and server links; replay always resolves fresh server URLs. */
class ArabLionzSourceProvider : WatchSourceProvider {
    private val embeds = TopCinemaSourceProvider()
    private val probe = StreamProbe(verifyHlsSegments = true)

    override suspend fun search(query: SourceQuery): List<WatchSource> = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext emptyList()
        val titles = aliases(query)
        val searches = titles.take(3).flatMap { title ->
            if (query.type == MediaType.TV) listOf("$title الموسم ${ORDINALS.getOrNull((query.season ?: 1) - 1) ?: query.season} الحلقة ${query.episode}", title)
            else listOf("$title ${query.year}", title)
        }.distinct()
        val visited = mutableSetOf<String>()
        for (text in searches) {
            val url = BASE.toHttpUrl().newBuilder().addQueryParameter("s", text).build()
            val results = read(url.toString())
            val links = results.select(".BlockItem a[href]").filter {
                sameSite(it.absUrl("href")) && titleMatches(it.selectFirst("h4")?.text().orEmpty(), query, titles, false)
            }.distinctBy { it.absUrl("href") }
            for (link in links.take(4)) {
                if (!visited.add(link.absUrl("href"))) continue
                try {
                    var doc = read(link.absUrl("href"))
                    resolve(doc, query, titles)?.let { return@withContext listOf(it) }
                    if (query.type != MediaType.TV) continue
                    val currentSeason = season(doc.selectFirst("h1.entry-title")?.text().orEmpty())
                    if (currentSeason != query.season) {
                        val target = doc.select(".SeasonsList a[href]").firstOrNull {
                            Regex("\\d+").find(arabic(it.text()))?.value?.toIntOrNull() == query.season && sameSite(it.absUrl("href"))
                        } ?: continue
                        doc = read(target.absUrl("href"))
                    }
                    val episode = doc.select(".EpisodesList .EpisodeItem a[href]").firstOrNull {
                        it.selectFirst("em")?.text()?.let(::arabic)?.toIntOrNull() == query.episode && sameSite(it.absUrl("href"))
                    } ?: continue
                    resolve(read(episode.absUrl("href")), query, titles)?.let { return@withContext listOf(it) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A bad candidate must not hide another matching post. */ }
            }
        }
        emptyList()
    }

    override suspend fun find(query: SourceQuery, id: String): WatchSource? = findPost(query, id, null)
    override suspend fun findVariant(query: SourceQuery, id: String, variantId: String): WatchSource? = findPost(query, id, variantId)

    private suspend fun findPost(query: SourceQuery, id: String, variantId: String?): WatchSource? = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext null
        val post = Regex("arablionz-(\\d{1,10})").matchEntire(id)?.groupValues?.get(1) ?: return@withContext null
        resolve(read("$BASE?p=$post"), query, aliases(query), variantId)
    }

    private suspend fun resolve(doc: Document, query: SourceQuery, titles: List<String>, targetId: String? = null): WatchSource? {
        if (!sameSite(doc.location())) return null
        val title = doc.selectFirst("h1.entry-title")?.text() ?: return null
        if (!titleMatches(title, query, titles, true)) return null
        val release = doc.select("li").firstOrNull { arabic(it.selectFirst("span")?.text().orEmpty()).startsWith("تاريخ الاصدار") }
        val year = release?.select("a[href*=/release-year/]")?.firstOrNull()?.text()?.let(::arabic)?.trim()?.toIntOrNull()
        if (year != query.year && (query.type != MediaType.TV || query.seasonYear == null || year != query.seasonYear)) return null
        val imdb = doc.select("a[href*=imdb.com/title/]").mapNotNull { Regex("tt\\d{7,10}").find(it.attr("href"))?.value }
        if (!query.imdbId.isNullOrBlank() && imdb.isNotEmpty() && query.imdbId !in imdb) return null
        val post = doc.selectFirst("link[rel=shortlink]")?.absUrl("href")?.toHttpUrlOrNull()?.queryParameter("p")
            ?.takeIf { it.matches(Regex("\\d{1,10}")) } ?: return null
        val watchUrl = doc.selectFirst(".WatchNow a[href]")?.absUrl("href")?.takeIf(::sameSite) ?: return null
        val watch = read(watchUrl)
        val servers = watch.select(".cwd-server-btn[data-server-url]").mapNotNull {
            val url = it.attr("data-server-url").toHttpUrlOrNull()?.takeIf { u -> u.isHttps } ?: return@mapNotNull null
            SourceWebLink(it.text().ifBlank { url.host }, url.toString())
        }.distinctBy { it.url }
        val downloadUrl = doc.selectFirst(".DownloadNow a[href]")?.absUrl("href")?.takeIf(::sameSite)
        val downloads = if (downloadUrl != null) try {
            read(downloadUrl).select("a.cwd-download-btn[href]").mapNotNull {
                val url = it.absUrl("href").toHttpUrlOrNull()?.takeIf { u -> u.isHttps } ?: return@mapNotNull null
                SourceWebLink(it.text().ifBlank { url.host }, url.toString(), download = true)
            }.distinctBy { it.url }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } else emptyList()
        val limit = Semaphore(3)
        val variants = coroutineScope {
            servers.withIndex().filter { (index, server) ->
                val host = server.url.toHttpUrlOrNull()?.host.orEmpty()
                (host == "movnet.site" || host.endsWith(".movnet.site")) && (targetId == null || targetId.startsWith("lionz-$index-"))
            }.map { (index, server) -> async {
                limit.withPermit {
                    try {
                        val host = server.url.toHttpUrl().host
                        embeds.resolveEmbed(server.url, watchUrl, server.label, index.toString(), setOf(host))
                            .map { it.copy(id = "lionz-$index-${it.transport.name.lowercase(java.util.Locale.ROOT)}-${it.id.substringAfterLast('-')}") }
                            .filter { targetId == null || it.id == targetId }
                            .filter { probe.compatible(it) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                }
            } }.awaitAll().flatten().distinctBy { it.url }
        }
        return WatchSource("arablionz-$post", "arablionz", "عرب ليونز", query, variants,
            attribution = "ArabLionz · $title · ${doc.location()}", licenceUrl = doc.location(),
            webLinks = servers + SourceWebLink("صفحة المشاهدة الأصلية", watchUrl) + downloads)
    }

    private fun titleMatches(raw: String, query: SourceQuery, titles: List<String>, complete: Boolean): Boolean {
        var text = arabic(raw).removePrefix("مشاهده ")
        val series = query.type == MediaType.TV
        if (series && !text.startsWith("مسلسل ") || !series && !text.startsWith("فيلم ")) return false
        text = text.substringAfter(' ')
        if (series) {
            if (complete && (season(text) != query.season || Regex("الحلقه\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() != query.episode)) return false
            text = text.substringBefore("الموسم").substringBefore("الحلقه")
        }
        text = text.substringBefore("مترجم").substringBefore("مدبلج").substringBefore("اونلاين").trim()
            .removeSuffix(query.year.toString()).trim().trim('(', ')', ' ')
        return titles.any { SourceQuery.titleKey(arabic(it)) == SourceQuery.titleKey(text) }
    }

    private fun season(value: String): Int? {
        val part = Regex("الموسم\\s+(.+?)(?:\\s+الحلقه|\\s+مترجم|\\s+مدبلج|\\s+كامل|$)").find(arabic(value))?.groupValues?.get(1)?.trim() ?: return null
        return part.toIntOrNull() ?: ORDINALS.indexOf(part).takeIf { it >= 0 }?.plus(1)
    }

    private fun aliases(query: SourceQuery) = listOfNotNull(query.searchTitle, query.originalTitle).flatMap {
        listOf(it.replace(Regex("^DC['’]s\\s+", RegexOption.IGNORE_CASE), ""), it)
    }.filter { it.isNotBlank() }.distinctBy { SourceQuery.titleKey(it) }

    private fun sameSite(url: String) = url.toHttpUrlOrNull()?.let { it.isHttps && it.host == HOST } == true
    private fun read(url: String): Document {
        if (!sameSite(url)) throw SourceException("رابط عرب ليونز غير متوافق")
        val request = Request.Builder().url(url).header("User-Agent", AGENT).header("Referer", BASE).build()
        return SourceHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || !sameSite(response.request.url.toString())) throw SourceException("تعذر الوصول إلى عرب ليونز (${response.code})")
            val source = response.body?.source() ?: throw SourceException("صفحة عرب ليونز فارغة")
            if (source.request(2 * 1024 * 1024L + 1)) throw SourceException("صفحة عرب ليونز أكبر من الحد المسموح")
            Jsoup.parse(source.readByteArray().toString(Charsets.UTF_8), response.request.url.toString())
        }
    }

    private fun arabic(value: String) = value.map { when (it) {
        'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ى' -> 'ي'; in '٠'..'٩' -> '0' + (it - '٠'); in '۰'..'۹' -> '0' + (it - '۰'); else -> it
    } }.joinToString("").trim()

    companion object {
        private const val HOST = "arablionz.live"
        private const val BASE = "https://arablionz.live/"
        private const val AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val ORDINALS = listOf("الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر",
            "الحادي عشر", "الثاني عشر", "الثالث عشر", "الرابع عشر", "الخامس عشر", "السادس عشر", "السابع عشر", "الثامن عشر", "التاسع عشر", "العشرون")
    }
}
