package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * TukTuk's "TukTuk Vip" server opens a Megamax mirror page (megatuktuk.top/iframe/<id>). That page
 * loads its server list as an Inertia partial ("streams"): every quality with its mirrors
 * (TukTukVIP, Firestream, vidara, mixdrop…). The list is read directly and each mirror is opened
 * with the existing embed reader; only probed, playable videos are returned.
 */
object TukTukMirrors {
    data class Result(val variants: List<SourceVariant>, val others: List<SourceWebLink>)

    private const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true }
    private val embeds = TopCinemaSourceProvider()
    private val probe = StreamProbe()

    /** A server button's data-link: reversed base64 with a few junk characters before "http". */
    fun decodeLink(dataLink: String): String? {
        val reversed = dataLink.substringBefore('&').reversed()
        val start = reversed.indexOf("aHR0").takeIf { it >= 0 } ?: return null
        val b64 = reversed.substring(start).trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) }
        return runCatching { String(java.util.Base64.getDecoder().decode(b64)) }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    /** go.php?u=<base64 target> download links. */
    fun decodeGo(url: String): String = url.toHttpUrlOrNull()?.queryParameter("u")
        ?.let { runCatching { String(java.util.Base64.getDecoder().decode(it)) }.getOrNull() }
        ?.takeIf { it.startsWith("http") } ?: url

    /** Mirrors that never give a playable link here (captcha / script-only players): listed, not tried. */
    private val SKIP = setOf("doodstream", "vidara", "byse")

    suspend fun resolve(mirrorPage: String, referer: String, onProgress: (String) -> Unit = {},
        onVariant: (SourceVariant) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val page = mirrorPage.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@withContext Result(emptyList(), emptyList())
        fun get(extra: Map<String, String> = emptyMap()): String = SourceHttp.client.newCall(Request.Builder().url(page)
            .header("User-Agent", AGENT).header("Referer", referer).apply { extra.forEach { (k, v) -> header(k, v) } }.build())
            .execute().use { r -> if (!r.isSuccessful) throw SourceException("Megamax ${r.code}"); r.body?.string().orEmpty() }
        val html = get()
        val version = Regex("\"version\":\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        val component = Regex("\"component\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.replace("\\/", "/") ?: "files/mirror/video"
        val body = get(mapOf("X-Inertia" to "true", "X-Inertia-Version" to version, "X-Inertia-Partial-Data" to "streams",
            "X-Inertia-Partial-Component" to component, "X-Requested-With" to "XMLHttpRequest", "Accept" to "text/html, application/xhtml+xml"))
        val qualities = json.parseToJsonElement(body).jsonObject["props"]?.jsonObject?.get("streams")?.jsonObject?.get("data")?.jsonArray.orEmpty()
        data class Mirror(val quality: String, val name: String, val url: String)
        val mirrors = qualities.flatMap { q ->
            val label = q.jsonObject["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
            q.jsonObject["mirrors"]?.jsonArray.orEmpty().mapNotNull { m ->
                val o = m.jsonObject
                val link = o["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = o["symbol"]?.jsonPrimitive?.contentOrNull ?: o["driver"]?.jsonPrimitive?.contentOrNull ?: "سيرفر"
                Mirror(label, name, if (link.startsWith("//")) "https:$link" else link)
            }
        }
        withContext(Dispatchers.Main) { onProgress("لقينا ${mirrors.size} سيرفر بـ ${qualities.size} جودة، نجربها…") }
        val gate = Semaphore(8)
        // Best quality first, TukTukVIP first within it: the first results show up quickest.
        val ordered = mirrors.withIndex().sortedBy { (i, m) -> if (m.name.contains("vip", true)) -1000 + i else i }
        val tried = coroutineScope {
            ordered.map { (i, m) -> async {
                if (SKIP.any { m.name.contains(it, true) }) return@async m to emptyList<SourceVariant>()
                gate.withPermit {
                    val host = m.url.toHttpUrlOrNull()?.host ?: return@withPermit m to emptyList<SourceVariant>()
                    val found = try {
                        embeds.resolveEmbed(m.url, page.toString(), "${m.quality} · ${m.name}", "tt$i", setOf(host))
                            .filter { probe.compatible(it) }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
                    found.firstOrNull()?.let { v -> withContext(Dispatchers.Main) { onVariant(v) } }
                    m to found.take(1)
                }
            } }.awaitAll()
        }
        Result(tried.flatMap { it.second }, tried.filter { it.second.isEmpty() }.map { SourceWebLink("${it.first.quality} · ${it.first.name}", it.first.url) })
    }
}
