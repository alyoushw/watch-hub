package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Website search and public server selection; temporary video URLs are resolved on replay. */
class TopCinemaSourceProvider : WatchSourceProvider {
    private val probe = StreamProbe()

    override suspend fun search(query: SourceQuery): List<WatchSource> = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext emptyList()
        val base = base()
        val aliases = listOfNotNull(query.searchTitle, query.originalTitle).filter { it.isNotBlank() }.distinctBy { SourceQuery.titleKey(it) }
        for (title in aliases.take(2)) {
            val text = if (query.type == MediaType.TV) "$title الموسم ${ORDINALS.getOrNull((query.season ?: 1) - 1) ?: query.season} الحلقة ${query.episode}" else title
            val url = base.newBuilder().addPathSegment("search").addPathSegment("")
                .addQueryParameter("query", text).addQueryParameter("type", if (query.type == MediaType.TV) "series" else "movies").build()
            val doc = read(url.toString(), base.toString()).doc
            val candidates = doc.select(".Small--Box a[href][title]").distinctBy { it.absUrl("href") }.filter {
                val page = it.absUrl("href").toHttpUrlOrNull()
                page?.host == base.host && titleMatches(it.attr("title"), query, aliases)
            }
            for (candidate in candidates.take(3)) {
                val source = safelyResolve(candidate.absUrl("href"), base, query, aliases)
                if (source != null) return@withContext listOf(source)
            }
        }
        emptyList()
    }

    override suspend fun find(query: SourceQuery, id: String): WatchSource? = withContext(Dispatchers.IO) {
        if (!query.valid) return@withContext null
        val post = Regex("topcinema-(\\d{1,10})").matchEntire(id)?.groupValues?.get(1) ?: return@withContext null
        val base = base()
        safelyResolve(base.newBuilder().addQueryParameter("p", post).build().toString(), base, query,
            listOfNotNull(query.originalTitle, query.searchTitle))
    }

    private suspend fun safelyResolve(url: String, base: HttpUrl, query: SourceQuery, aliases: List<String>): WatchSource? = try {
        resolvePage(read(url, base.toString()).doc, base, query, aliases)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { null }

    private suspend fun resolvePage(doc: Document, base: HttpUrl, query: SourceQuery, aliases: List<String>): WatchSource? {
        if (doc.location().toHttpUrlOrNull()?.host != base.host) return null
        val title = doc.selectFirst("h1.post-title")?.text() ?: return null
        if (!titleMatches(title, query, aliases)) return null
        val yearRow = doc.select("li").firstOrNull { row ->
            row.children().any { it.tagName() == "span" && "موعد الصدور" in it.text() }
        } ?: return null
        if (Regex("\\d{4}").find(arabic(yearRow.text()))?.value?.toIntOrNull() != query.year) return null
        val claimedImdb = doc.select("a[href*=imdb.com/title/]").mapNotNull { Regex("tt\\d{7,10}").find(it.attr("href"))?.value }
        if (!query.imdbId.isNullOrBlank() && claimedImdb.isNotEmpty() && query.imdbId !in claimedImdb) return null
        val shortlink = doc.selectFirst("input#shortlink")?.attr("value")?.toHttpUrlOrNull() ?: return null
        val post = shortlink.queryParameter("p")?.takeIf { it.matches(Regex("\\d{1,10}")) } ?: return null
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href")?.toHttpUrlOrNull()
            ?.takeIf { it.host == base.host } ?: doc.location().toHttpUrlOrNull() ?: return null
        val watchUrl = canonical.newBuilder().query(null).addPathSegment("watch").addPathSegment("").build()
        val watch = read(watchUrl.toString(), canonical.toString()).doc
        val servers = watch.select("li.server--item[data-id][data-server]").filter {
            it.attr("data-id") == post && it.attr("data-server").matches(Regex("\\d{1,3}")) && supported(it.text())
        }.sortedBy { priority(it.text()) }
        val variants = mutableListOf<SourceVariant>()
        for (server in servers.take(4)) {
            val name = server.selectFirst("span")?.text()?.ifBlank { null } ?: server.text()
            val index = server.attr("data-server")
            try {
                val response = read(base.resolve("/wp-content/themes/movies2023/Ajaxat/Single/Server.php")!!.toString(), watchUrl.toString(),
                    FormBody.Builder().add("id", post).add("i", index).build())
                val embed = response.doc.selectFirst("iframe[src]")?.absUrl("src") ?: continue
                for (variant in resolveEmbed(embed, watchUrl.toString(), name, index)) {
                    if (probe.compatible(variant)) { variants += variant; break }
                }
                if (variants.size >= 2) break
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* A missing/blocked host must not hide other working servers. */ }
        }
        if (variants.isEmpty()) return null
        return WatchSource("topcinema-$post", "topcinema", "TopCinema", query, variants,
            attribution = "TopCinema · ${query.originalTitle} (${query.year}) · $canonical", licenceUrl = canonical.toString())
    }

    private fun resolveEmbed(url: String, referer: String, name: String, index: String): List<SourceVariant> {
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps && embedHost(it.host) } ?: return emptyList()
        val page = read(parsed.toString(), referer)
        val origin = page.doc.location().toHttpUrlOrNull() ?: return emptyList()
        val scripts = page.doc.select("script:not([src])").map { it.data() }
        val decoded = scripts.flatMap { script -> listOf(script) + unpack(script) }
        return decoded.filter { "sources" in it || "MDCore" in it }.flatMap { script ->
            MEDIA_FIELD.findAll(script).mapNotNull { match ->
                val key = match.groupValues[1]
                val value = unquote(match.groupValues[2])
                val media = origin.resolve(value)?.takeIf { it.isHttps } ?: return@mapNotNull null
                val path = media.encodedPath.lowercase(java.util.Locale.ROOT)
                val type = when {
                    key.startsWith("hls") || ".m3u8" in path || "/hls" in path || path.endsWith("/master.txt") -> SourceTransport.HLS
                    path.endsWith(".mp4") -> SourceTransport.MP4
                    path.endsWith(".mkv") -> SourceTransport.MKV
                    else -> return@mapNotNull null
                }
                val headers = mutableMapOf("User-Agent" to AGENT, "Referer" to origin.toString())
                val cookie = page.cookies.filter { it.matches(media) }.joinToString("; ") { "${it.name}=${it.value}" }
                if (cookie.isNotBlank()) headers["Cookie"] = cookie
                Triple(media.toString(), type, headers.toMap())
            }.toList()
        }.distinctBy { it.first }.take(3).mapIndexed { number, (media, type, headers) ->
            SourceVariant("server-$index-$number", "$name · ${type.name}", media, type, headers)
        }
    }

    /** Decode the static Packer string table; never execute website JavaScript. */
    private fun unpack(script: String): List<String> = PACKER.findAll(script).take(4).mapNotNull { match ->
        val radix = match.groupValues[2].toIntOrNull()?.takeIf { it in 2..36 } ?: return@mapNotNull null
        val count = match.groupValues[3].toIntOrNull()?.takeIf { it in 1..5000 } ?: return@mapNotNull null
        val words = unquote(match.groupValues[4]).split('|').take(5000)
        val payload = unquote(match.groupValues[1])
        Regex("\\b[0-9a-z]+\\b").replace(payload) { token ->
            val position = token.value.toIntOrNull(radix)
            if (position != null && position < count) words.getOrNull(position)?.ifEmpty { null } ?: token.value else token.value
        }
    }.toList()

    private fun unquote(value: String): String = Regex("\\\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|.)").replace(value) { match ->
        val escaped = match.groupValues[1]
        when {
            escaped.length > 1 && escaped[0] in "ux" -> escaped.substring(1).toInt(16).toChar().toString()
            escaped == "n" -> "\n"; escaped == "r" -> "\r"; escaped == "t" -> "\t"
            else -> escaped
        }
    }

    private fun titleMatches(raw: String, query: SourceQuery, aliases: List<String>): Boolean {
        val series = query.type == MediaType.TV
        if (raw.startsWith("مسلسل ") != series || !series && !raw.startsWith("فيلم ")) return false
        val title = if (series) {
            val episode = Regex("الموسم\\s+(.+?)\\s+الحلقه\\s+(\\d+)").find(arabic(raw)) ?: return false
            val season = episode.groupValues[1].trim().let { it.toIntOrNull() ?: ORDINALS.indexOf(it).takeIf { index -> index >= 0 }?.plus(1) }
            if (season != query.season || episode.groupValues[2].toIntOrNull() != query.episode) return false
            raw.removePrefix("مسلسل ").substringBefore("الموسم").trim()
        } else {
            if (query.year.toString() !in raw) return false
            raw.removePrefix("فيلم ").substringBefore("مترجم").substringBefore("مدبلج").trim()
                .removeSuffix(query.year.toString()).trim().trim('(', ')', '[', ']', ' ')
        }
        return aliases.any { SourceQuery.titleKey(it) == SourceQuery.titleKey(title) }
    }

    private fun base(): HttpUrl = read(START).doc.location().toHttpUrl().newBuilder().encodedPath("/").query(null).build()
    private data class Page(val doc: Document, val cookies: List<Cookie>)
    private fun read(url: String, referer: String = START, form: FormBody? = null): Page {
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: throw SourceException("رابط TopCinema غير متوافق")
        val request = Request.Builder().url(parsed).header("User-Agent", AGENT).header("Referer", referer)
            .apply { if (form != null) post(form).header("X-Requested-With", "XMLHttpRequest") }.build()
        return SourceHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SourceException("تعذر الوصول إلى TopCinema أو السيرفر (${response.code})")
            val input = response.body?.source() ?: throw SourceException("رد TopCinema فارغ")
            if (input.request(2 * 1024 * 1024L + 1)) throw SourceException("صفحة TopCinema أكبر من الحد المسموح")
            Page(Jsoup.parse(input.readByteArray().toString(Charsets.UTF_8), response.request.url.toString()), Cookie.parseAll(response.request.url, response.headers))
        }
    }

    private fun supported(name: String) = listOf("uqload", "streamwish", "videotube", "updown").any { name.contains(it, true) }
    private fun priority(name: String) = when { name.contains("uqload", true) -> 0; name.contains("streamwish", true) -> 1; else -> 2 }
    private fun embedHost(host: String) = listOf("uqload.net", "uqload.vc", "streamwish.fun", "vidtube.one", "updown.cam")
        .any { host == it || host.endsWith(".$it") }
    private fun arabic(text: String) = text.map { char -> when (char) {
        'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ى' -> 'ي'; in '٠'..'٩' -> '0' + (char - '٠'); in '۰'..'۹' -> '0' + (char - '۰'); else -> char
    } }.joinToString("").trim()

    companion object {
        private const val START = "https://web6.topcinema.fan/"
        private const val AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val MEDIA_FIELD = Regex("""\b(hls[234]?|file|src|source|wurl)\s*["']?\s*[:=]\s*["']([^"']+)["']""")
        private val PACKER = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\}\('((?:\\.|[^'\\])*)',(\d+),(\d+),'((?:\\.|[^'\\])*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
        private val ORDINALS = listOf("الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر",
            "الحادي عشر", "الثاني عشر", "الثالث عشر", "الرابع عشر", "الخامس عشر", "السادس عشر", "السابع عشر", "الثامن عشر", "التاسع عشر", "العشرون",
            "الحادي والعشرون", "الثاني والعشرون", "الثالث والعشرون", "الرابع والعشرون", "الخامس والعشرون", "السادس والعشرون", "السابع والعشرون", "الثامن والعشرون", "التاسع والعشرون", "الثلاثون")
    }
}
