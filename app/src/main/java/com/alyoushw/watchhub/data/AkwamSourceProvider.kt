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
import java.util.Base64

/** Download landing pages are resolved to checked video streams; HTML never goes to Media3. */
class AkwamSourceProvider(private val api: TmdbApi) : WatchSourceProvider {
    private val embeds = TopCinemaSourceProvider()
    private val probe = StreamProbe(verifyHlsSegments = true)

    override suspend fun search(query: SourceQuery): List<WatchSource> = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext emptyList()
        val titles = aliases(query)
        val visited = mutableSetOf<String>()
        var failed: Exception? = null
        for (title in titles.take(3)) {
            val url = BASE.toHttpUrl().resolve("search")!!.newBuilder().addQueryParameter("q", title).build()
            val doc = read(url.toString())
            val links = doc.select("a.br-card-link[href]").filter {
                sameSite(it.absUrl("href")) && matches(it.selectFirst(".br-card-title")?.text().orEmpty(), query, titles, false)
            }.distinctBy { it.absUrl("href") }
            for (link in links.take(4)) {
                if (!visited.add(link.absUrl("href"))) continue
                try {
                    var details = read(link.absUrl("href"))
                    if (query.type == MediaType.TV && episode(details.title()) != query.episode) {
                        val item = details.select(".br-episode-grid a[href]").firstOrNull {
                            Regex("\\d+").find(arabic(it.text()))?.value?.toIntOrNull() == query.episode && sameSite(it.absUrl("href"))
                        } ?: continue
                        details = read(item.absUrl("href"))
                    }
                    resolve(details, query, titles)?.let { return@withContext listOf(it) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failed = e }
            }
        }
        if (failed != null) throw SourceException("وجد البحث نتيجة محتملة، لكن تعذّر التحقق من تفاصيلها؛ أعد المحاولة", (failed as? SourceException)?.httpStatus)
        emptyList()
    }

    override suspend fun find(query: SourceQuery, id: String): WatchSource? = findPost(query, id, null)
    override suspend fun findVariant(query: SourceQuery, id: String, variantId: String): WatchSource? = findPost(query, id, variantId)
    private suspend fun findPost(query: SourceQuery, id: String, variantId: String?): WatchSource? = withContext(Dispatchers.IO) {
        if (!query.valid || !id.startsWith("akw-")) return@withContext null
        val slug = runCatching { String(Base64.getUrlDecoder().decode(id.removePrefix("akw-")), Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,110}")) } ?: return@withContext null
        resolve(read(BASE.toHttpUrl().newBuilder().addPathSegment(slug).addPathSegment("").build().toString()), query, aliases(query), variantId)
    }

    private suspend fun resolve(doc: Document, query: SourceQuery, titles: List<String>, targetId: String? = null): WatchSource? {
        val title = doc.selectFirst("h1")?.text() ?: return null
        if (!matches(title, query, titles, true)) return null
        val page = doc.location().toHttpUrlOrNull()?.takeIf { it.host == HOST && it.isHttps } ?: return null
        val slug = page.encodedPath.trim('/').takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,110}")) } ?: return null
        val id = "akw-" + Base64.getUrlEncoder().withoutPadding().encodeToString(slug.toByteArray(Charsets.UTF_8))
        val verified = if (query.type == MediaType.MOVIE) query.year.toString() in arabic(title) else confirmSeries(query, titles)
        if (!verified) return WatchSource(id, "akwam", "أكوام", query, emptyList(), attribution = "Akwam · $title", licenceUrl = page.toString(),
            webLinks = listOf(SourceWebLink("نتيجة محتملة — افتح التفاصيل للتحقق", page.toString())),
            issues = listOf(SourceIssue("مطابقة العمل", SourceHealth.UNVERIFIED)), matchVerified = false)
        val downloadUrl = doc.selectFirst("a[href*=/download/]")?.absUrl("href")?.takeIf(::sameSite) ?: return null
        val download = try { read(downloadUrl) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return WatchSource(id, "akwam", "أكوام", query, emptyList(), attribution = "Akwam · $title", licenceUrl = page.toString(),
            webLinks = listOf(SourceWebLink("صفحة التحميل الأصلية", downloadUrl, true)), issues = listOf(sourceFailure("صفحة التحميل", e))) }
        val links = download.select(".br-download-row").mapNotNull { row ->
            val url = row.selectFirst("a[href]")?.absUrl("href")?.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@mapNotNull null
            SourceWebLink((row.selectFirst("strong")?.text() ?: "تحميل") + " · " + url.host, url.toString(), true)
        }.distinctBy { it.url }
        val issues = java.util.Collections.synchronizedList(mutableListOf<SourceIssue>())
        val watchPages = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, SourceWebLink>>())
        val limit = Semaphore(3)
        val variants = coroutineScope {
            links.withIndex().map { (index, link) -> async {
                if (targetId != null && !targetId.startsWith("download-$index-")) return@async emptyList<SourceVariant>()
                limit.withPermit {
                    try {
                        val url = link.url.toHttpUrl()
                        val media = when {
                            url.encodedPath.endsWith(".mp4", true) -> listOf(SourceVariant("direct", link.label, link.url, SourceTransport.MP4))
                            url.host == "lulustream.com" && url.encodedPath.startsWith("/d/") ->
                                embeds.resolveEmbed(link.url, downloadUrl, link.label, index.toString(), setOf("lulustream.com")) { hostPage ->
                                    val key = " ${SourceQuery.titleKey(hostPage.title())} "
                                    val named = titles.any { " ${SourceQuery.titleKey(it)} " in key }
                                    val accepted = named && if (query.type == MediaType.TV)
                                        Regex("(?i)s0*${query.season}[ ._-]*e0*${query.episode}(?![0-9])").containsMatchIn(hostPage.title())
                                    else Regex("(?<![0-9])${query.year}(?![0-9])").containsMatchIn(hostPage.title())
                                    if (accepted) watchPages += discoverWatchPages(hostPage, link).map { index to it }
                                    accepted
                                }
                            url.host in MIXDROP_HOSTS && url.encodedPath.startsWith("/f/") -> {
                                val hostPage = readDownloadHost(link.url, downloadUrl)
                                watchPages += discoverWatchPages(hostPage, link).map { index to it }
                                issues += SourceIssue(link.label, SourceHealth.UNSUPPORTED)
                                emptyList()
                            }
                            else -> { issues += SourceIssue(link.label, SourceHealth.UNSUPPORTED); emptyList() }
                        }
                        if (media.isEmpty() && url.host == "lulustream.com") issues += SourceIssue(link.label, SourceHealth.UNVERIFIED)
                        media.map { it.copy(id = "download-$index-${it.transport.name.lowercase(java.util.Locale.ROOT)}-${it.id.substringAfterLast('-')}",
                            label = link.label + " · " + it.transport.name + " · مشاهدة من رابط التحميل") }
                            .filter { targetId == null || it.id == targetId }.filter { variant ->
                                val check = probe.inspect(variant)
                                if (!check.playable) issues += check.issue(link.label)
                                check.playable
                            }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { issues += sourceFailure(link.label, e); emptyList() }
                }
            } }.awaitAll().flatten().distinctBy { it.url }
        }
        return WatchSource(id, "akwam", "أكوام", query, variants, attribution = "Akwam · $title", licenceUrl = page.toString(),
            webLinks = watchPages.toList().sortedBy { it.first }.map { it.second }.distinctBy { it.url } +
                links + SourceWebLink("صفحة العمل الأصلية", page.toString()), issues = issues.toList().distinct())
    }

    /** Follow only an embed actually declared by the download page for the same file ID. */
    private fun discoverWatchPages(doc: Document, link: SourceWebLink): List<SourceWebLink> {
        val owner = link.url.toHttpUrl()
        val id = owner.pathSegments.lastOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{4,80}")) } ?: return emptyList()
        val hosts = if (owner.host == "lulustream.com") setOf("lulustream.com", "luluvid.com") else MIXDROP_HOSTS
        return doc.select("iframe[src]").mapNotNull {
            val url = it.absUrl("src").toHttpUrlOrNull()?.takeIf { u ->
                u.isHttps && u.host in hosts && u.encodedPath.trim('/') == "e/$id"
            } ?: return@mapNotNull null
            SourceWebLink(link.label.replaceFirst("تحميل", "مشاهدة"), url.toString())
        }.distinctBy { it.url }
    }

    private fun readDownloadHost(url: String, referer: String): Document {
        require(url.toHttpUrl().host in MIXDROP_HOSTS)
        return SourceHttp.client.newCall(Request.Builder().url(url).header("User-Agent", AGENT).header("Referer", referer).build()).execute().use { response ->
            if (!response.isSuccessful) throw SourceException("تعذّر فتح صفحة الخادم (${response.code})", response.code)
            if (response.request.url.host !in MIXDROP_HOSTS) throw SourceException("تعذّر تأكيد عنوان صفحة الخادم")
            val body = response.body?.source() ?: throw SourceException("صفحة الخادم فارغة")
            if (body.request(2 * 1024 * 1024L + 1)) throw SourceException("صفحة الخادم أكبر من الحد المسموح")
            checkedSourcePage(Jsoup.parse(body.readByteArray().toString(Charsets.UTF_8), response.request.url.toString()))
        }
    }

    private suspend fun confirmSeries(query: SourceQuery, titles: List<String>): Boolean {
        // The site exposes upload dates, not a trustworthy debut year. Confirm a unique TMDB identity instead.
        for (title in titles.take(2)) try {
            val result = api.searchPage(title)
            if (result.totalPages == 1 && result.results.filter { it.type == MediaType.TV }.singleOrNull()?.id == query.tmdbId) return true
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Keep uncertain matches website-only. */ }
        return false
    }

    private fun matches(raw: String, query: SourceQuery, titles: List<String>, full: Boolean): Boolean {
        val text = arabic(raw)
        val series = query.type == MediaType.TV
        if (series && !text.startsWith("مسلسل ") || !series && !text.contains("فيلم ")) return false
        if (series && full && (episode(text) != query.episode || season(text) != query.season)) return false
        if (!series && query.year.toString() !in text) return false
        val name = (if (series) text.removePrefix("مسلسل ") else text.substringAfter("فيلم "))
            .substringBefore("الموسم").substringBefore("الحلقه").substringBefore("مترجم").substringBefore("مدبلج")
            .substringBefore("اون لاين").trim().removeSuffix(query.year.toString()).trim()
        val english = Regex("[A-Za-z].*").find(name)?.value
        val names = listOfNotNull(name, english, english?.let { name.substringBefore(it).trim() }).map { SourceQuery.titleKey(it) }
        return titles.any { SourceQuery.titleKey(arabic(it)) in names }
    }

    private fun episode(text: String) = Regex("الحلقه\\s*(\\d+)").find(arabic(text))?.groupValues?.get(1)?.toIntOrNull()
    private fun season(text: String): Int? {
        val value = Regex("الموسم\\s+(\\d+|الاول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر)").find(arabic(text))?.groupValues?.get(1) ?: return 1
        return value.toIntOrNull() ?: ORDINALS.indexOf(value).takeIf { it >= 0 }?.plus(1)
    }
    private fun aliases(query: SourceQuery) = listOfNotNull(query.searchTitle, query.originalTitle).filter { it.isNotBlank() }.distinctBy { SourceQuery.titleKey(it) }
    private fun sameSite(url: String) = url.toHttpUrlOrNull()?.let { it.isHttps && it.host == HOST } == true
    private fun read(url: String): Document {
        if (!sameSite(url)) throw SourceException("رابط أكوام غير متوافق")
        return SourceHttp.client.newCall(Request.Builder().url(url).header("User-Agent", AGENT).header("Referer", BASE).build()).execute().use { response ->
            if (!response.isSuccessful || !sameSite(response.request.url.toString())) throw SourceException("تعذر الوصول إلى أكوام (${response.code})", response.code)
            val source = response.body?.source() ?: throw SourceException("رد أكوام فارغ")
            if (source.request(2 * 1024 * 1024L + 1)) throw SourceException("صفحة أكوام أكبر من الحد المسموح")
            checkedSourcePage(Jsoup.parse(source.readByteArray().toString(Charsets.UTF_8), response.request.url.toString()))
        }
    }
    private fun arabic(text: String) = text.map { when (it) {
        'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ى' -> 'ي'; in '٠'..'٩' -> '0' + (it - '٠'); in '۰'..'۹' -> '0' + (it - '۰'); else -> it
    } }.joinToString("").trim()
    companion object {
        private const val HOST = "akw.cam"
        private const val BASE = "https://akw.cam/"
        private const val AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val MIXDROP_HOSTS = setOf("mixdrop.ag", "mixdrop.top", "mixdrop.ps", "mixdrop.co", "mixdrop.to", "mxdrop.click")
        private val ORDINALS = listOf("الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر")
    }
}
