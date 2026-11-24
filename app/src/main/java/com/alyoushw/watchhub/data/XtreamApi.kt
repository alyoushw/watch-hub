package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One of the user's Xtream Codes logins from their providers, with the name they gave it. */
@kotlinx.serialization.Serializable
data class XtreamAccount(
    val host: String,
    val username: String,
    val password: String,
    val id: String = "",
    val name: String = "",
) {
    val isComplete: Boolean get() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** What to call it on buttons: the user's name for it, else the server's host. */
    val label: String get() = name.ifBlank { host.substringAfter("://").substringBefore("/") }

    companion object {
        /**
         * The same id for the same login on every device (phone, TV, web), so synced history
         * points at the right account everywhere: FNV-1a of "host|username", base 36.
         * The web app computes it the same way (store.js stableAccountId).
         */
        fun stableId(host: String, username: String): String {
            val text = XtreamApi.normalizeHost(host).lowercase() + "|" + username.trim()
            var h = 0x811C9DC5L
            for (c in text) {
                h = h xor c.code.toLong()
                h = (h * 0x01000193L) and 0xFFFFFFFFL
            }
            return "x" + h.toString(36)
        }

        /**
         * Parses one or more accounts from pasted text. It accepts, in any mix:
         *  - one line per account: "name | host | user | pass" (separators | , ; or tab);
         *  - an Xtream URL: …/get.php?username=U&password=P;
         *  - labelled fields on their own lines in any order (الاسم/الهوست/المستخدم/المرور, or
         *    name/host/user/pass), one account per block, blocks split by a blank line.
         */
        fun parseList(text: String): List<XtreamAccount> {
            val records = mutableListOf<MutableList<String>>()
            var cur = mutableListOf<String>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) { if (cur.isNotEmpty()) { records.add(cur); cur = mutableListOf() } }
                else cur.add(line)
            }
            if (cur.isNotEmpty()) records.add(cur)
            return records.flatMap { parseRecord(it) }.distinctBy { it.id }
        }

        private fun build(name: String, host: String, user: String, pass: String): XtreamAccount? {
            val h = XtreamApi.normalizeHost(host.trim())
            val u = user.trim()
            if (h.isBlank() || u.isBlank() || pass.isBlank()) return null
            return XtreamAccount(h, u, pass.trim(), stableId(h, u), name.trim())
        }

        /** Which field a label names, so the pasted order does not matter. */
        private fun labelKey(raw: String): String? {
            val s = raw.trim().lowercase()
            return when {
                s.contains("مستخدم") || s.contains("user") -> "user"
                s.contains("مرور") || s.contains("باسورد") || s.contains("pass") -> "pass"
                s.contains("هوست") || s.contains("رابط") || s.contains("host") || s.contains("url") || s.contains("سيرفر") || s.contains("server") -> "host"
                s.contains("اسم") || s == "name" -> "name"
                else -> null
            }
        }

        private fun parseRecord(lines: List<String>): List<XtreamAccount> {
            val fields = linkedMapOf<String, String>()
            lines.forEach { line ->
                val m = Regex("^([\\p{L} ]+?)\\s*[:=]\\s*(.+)$").find(line)
                val key = m?.let { labelKey(it.groupValues[1]) }
                if (key != null && key !in fields) fields[key] = m!!.groupValues[2].trim()
            }
            fields["host"]?.let { return listOfNotNull(build(fields["name"].orEmpty(), it, fields["user"].orEmpty(), fields["pass"].orEmpty())) }
            if (lines.size == 1) return listOfNotNull(parseOne(lines[0]))
            // Bare values, one per line: find the host, then user and pass follow it.
            val hostIdx = lines.indexOfFirst { it.contains("://") || Regex("\\.[A-Za-z]{2,}").containsMatchIn(it) }
            if (hostIdx in 0..(lines.size - 3)) {
                return listOfNotNull(build(lines.take(hostIdx).joinToString(" "), lines[hostIdx], lines[hostIdx + 1], lines[hostIdx + 2]))
            }
            return emptyList()
        }

        private fun parseOne(line: String): XtreamAccount? {
            if (line.isBlank()) return null
            // An Xtream URL carries the login in its query.
            if (line.contains("://") && line.contains("username=", ignoreCase = true)) {
                val url = line.toHttpUrlOrNull() ?: return null
                val user = url.queryParameter("username") ?: return null
                val pass = url.queryParameter("password") ?: return null
                val host = url.scheme + "://" + url.host + ":" + url.port
                return build("", host, user, pass)
            }
            val parts = line.split(Regex("[|,;\\t]")).map { it.trim() }.filter { it.isNotEmpty() }
            val hostIdx = parts.indexOfFirst { it.contains("://") || Regex("\\.[A-Za-z]{2,}").containsMatchIn(it) }
            if (hostIdx < 0 || hostIdx + 2 > parts.lastIndex) return null
            val name = parts.take(hostIdx).joinToString(" ")
            return build(name, parts[hostIdx], parts[hostIdx + 1], parts[hostIdx + 2])
        }
    }
}

/** What the provider says about the account (from player_api.php's user_info). */
data class XtreamAccountInfo(
    val status: String?,
    val expiresAt: Date?,
    val maxConnections: String?,
    val activeConnections: String?,
)

/** The three kinds of content an Xtream Codes account has. */
@kotlinx.serialization.Serializable
enum class XtreamKind(val label: String, val categoriesAction: String, val listAction: String, val pathSegment: String) {
    LIVE("مباشر", "get_live_categories", "get_live_streams", "live"),
    MOVIE("الأفلام", "get_vod_categories", "get_vod_streams", "movie"),
    SERIES("المسلسلات", "get_series_categories", "get_series", "series"),
}

data class XtreamCategory(val id: String, val name: String)

/** A channel, a film or a series in the account. */
@kotlinx.serialization.Serializable
data class XtreamItem(
    val kind: XtreamKind,
    val id: String,
    val name: String,
    val image: String?,
    val extension: String?,
    val rating: String?,
    val categoryId: String? = null,
    /** Channel number from the provider. */
    val num: Int? = null,
    /** TMDB id when the provider has it, used to match a title page to the account. */
    val tmdb: String? = null,
    val year: String? = null,
    val directUrl: String? = null,
)

/** One programme from the channel guide. */
data class XtreamProgram(val title: String, val start: Date?, val end: Date?)

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String?,
    val image: String?,
)

data class XtreamSeries(
    val name: String,
    val plot: String?,
    val cover: String?,
    val episodes: List<XtreamEpisode>,
)

class XtreamException(message: String) : ApiException(message)

/** One entry of an Xtream list, only the fields the app uses (values may be numbers or text). */
@kotlinx.serialization.Serializable
internal class RawItem(
    @kotlinx.serialization.SerialName("stream_id") val streamId: JsonElement? = null,
    @kotlinx.serialization.SerialName("series_id") val seriesId: JsonElement? = null,
    val name: JsonElement? = null,
    val title: JsonElement? = null,
    val cover: JsonElement? = null,
    @kotlinx.serialization.SerialName("stream_icon") val streamIcon: JsonElement? = null,
    @kotlinx.serialization.SerialName("container_extension") val containerExtension: JsonElement? = null,
    val rating: JsonElement? = null,
    @kotlinx.serialization.SerialName("category_id") val categoryId: JsonElement? = null,
    val num: JsonElement? = null,
    val tmdb: JsonElement? = null,
    @kotlinx.serialization.SerialName("tmdb_id") val tmdbId: JsonElement? = null,
    @kotlinx.serialization.SerialName("direct_source") val directSource: JsonElement? = null,
    val year: JsonElement? = null,
    val releaseDate: JsonElement? = null,
    @kotlinx.serialization.SerialName("release_date") val releaseDate2: JsonElement? = null,
)

/** Minimal client for the Xtream Codes player API: checks the login and lists the account's content. */
class XtreamApi(private val settings: SettingsStore) {
    // Shares Net's pool, which is dropped when the phone changes network.
    private val client = Net.streams.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** The account shown on the channels / films / series screens. */
    private val active: XtreamAccount get() = settings.xtream.value

    /** Logs in with [account] and returns the account details, or throws [XtreamException]. */
    suspend fun check(account: XtreamAccount): XtreamAccountInfo {
        val root = call(account, action = null) as? JsonObject ?: throw notXtream()
        val user = root["user_info"] as? JsonObject ?: throw notXtream()
        if (user.text("auth") != "1") throw XtreamException("اسم المستخدم أو كلمة المرور غلط")
        return XtreamAccountInfo(
            status = user.text("status"),
            expiresAt = user.text("exp_date")?.toLongOrNull()?.let { Date(it * 1000) },
            maxConnections = user.text("max_connections"),
            activeConnections = user.text("active_cons"),
        )
    }

    suspend fun categories(kind: XtreamKind, account: XtreamAccount = active): List<XtreamCategory> =
        (call(account, kind.categoriesAction) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { c ->
                val id = c.text("category_id") ?: return@mapNotNull null
                XtreamCategory(id, c.text("category_name") ?: id)
            }
            .distinctBy { it.id }

    suspend fun items(kind: XtreamKind, categoryId: String?, account: XtreamAccount = active): List<XtreamItem> {
        val params = categoryId?.let { mapOf("category_id" to it) }.orEmpty()
        val rows: List<RawItem> = if (account.id == FourU.account.id) {
            (call(account, kind.listAction, params) as? JsonArray).orEmpty()
                .mapNotNull { runCatching { json.decodeFromJsonElement(RawItem.serializer(), it) }.getOrNull() }
        } else {
            streamList(account, kind.listAction, params)
        }
        return rows.mapNotNull { o ->
            val id = (if (kind == XtreamKind.SERIES) o.seriesId.str() else o.streamId.str()) ?: return@mapNotNull null
            XtreamItem(
                kind = kind,
                id = id,
                name = o.name.str() ?: o.title.str() ?: id,
                image = (if (kind == XtreamKind.SERIES) o.cover.str() else o.streamIcon.str())?.takeIf { it.startsWith("http") },
                extension = o.containerExtension.str(),
                rating = o.rating.str()?.takeIf { it != "0" && it != "0.0" },
                categoryId = o.categoryId.str(),
                num = o.num.str()?.toIntOrNull(),
                tmdb = (o.tmdb.str() ?: o.tmdbId.str())?.takeIf { it != "0" },
                directUrl = o.directSource.str(),
                year = o.year.str()?.take(4)
                    ?: (o.releaseDate.str() ?: o.releaseDate2.str())?.take(4)?.takeIf { it.length == 4 },
            )
        }.distinctBy { it.id }
    }

    /**
     * A channel / film / series list read straight from the network one entry at a time: no copy of
     * the whole reply as text or as a JSON tree (tens of thousands of entries ran TVs out of memory).
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun streamList(account: XtreamAccount, action: String, params: Map<String, String>): List<RawItem> =
        withContext(Dispatchers.IO) {
            if (!account.isComplete) throw XtreamException("أضف حساب Xtream Codes من الإعدادات أول")
            try {
                client.newCall(Request.Builder().url(urlFor(account, action, params)).build()).execute().use { response ->
                    if (response.code == 401 || response.code == 403) throw XtreamException("اسم المستخدم أو كلمة المرور غلط")
                    if (!response.isSuccessful) throw XtreamException("السيرفر رد بخطأ (${response.code})")
                    val input = java.io.BufferedInputStream(response.body?.byteStream() ?: return@use emptyList(), 64 * 1024)
                    // Some servers answer an empty list with {} or nothing.
                    input.mark(256)
                    var first = input.read()
                    while (first != -1 && first.toChar().isWhitespace()) first = input.read()
                    input.reset()
                    if (first != '['.code) return@use emptyList()
                    json.decodeToSequence(input, RawItem.serializer(), kotlinx.serialization.json.DecodeSequenceMode.ARRAY_WRAPPED).toList()
                }
            } catch (e: XtreamException) {
                throw e
            } catch (e: IOException) {
                throw XtreamException("ما قدرنا نوصل للسيرفر، تأكد من الهوست والإنترنت")
            } catch (e: kotlinx.serialization.SerializationException) {
                throw notXtream()
            }
        }

    private val cache = mutableMapOf<String, List<XtreamItem>>()
    private val cacheLock = Mutex()

    private val keyLocks = mutableMapOf<String, Mutex>()

    /**
     * Every item of [kind] in the account, loaded once per login and kept in memory. One lock per
     * account, so several accounts load side by side instead of one after another.
     */
    suspend fun all(kind: XtreamKind, account: XtreamAccount = active): List<XtreamItem> {
        val key = "${account.host}|${account.username}|$kind"
        val lock = cacheLock.withLock {
            cache[key]?.let { return it }
            keyLocks.getOrPut(key) { Mutex() }
        }
        return lock.withLock {
            cacheLock.withLock { cache[key] }?.let { return it }
            val list = items(kind, null, account)
            if (list.isNotEmpty()) cacheLock.withLock { cache[key] = list }
            list
        }
    }

    /** Now and next on a channel (titles come base64-encoded). */
    suspend fun shortEpg(streamId: String, account: XtreamAccount = active): List<XtreamProgram> {
        val root = call(account, "get_short_epg", mapOf("stream_id" to streamId, "limit" to "2")) as? JsonObject
            ?: return emptyList()
        return (root["epg_listings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { p ->
            val raw = p.text("title") ?: return@mapNotNull null
            XtreamProgram(
                title = decodeBase64(raw),
                start = p.text("start_timestamp")?.toLongOrNull()?.let { Date(it * 1000) },
                end = (p.text("stop_timestamp") ?: p.text("end_timestamp"))?.toLongOrNull()?.let { Date(it * 1000) },
            )
        }
    }

    /**
     * Finds a film or series from a title page in the account: by TMDB id when the provider
     * lists it, otherwise by name (Arabic or English), preferring the same year.
     */
    suspend fun findMatch(
        kind: XtreamKind,
        tmdbId: Int,
        titles: List<String>,
        year: String?,
        account: XtreamAccount = active,
    ): XtreamItem? = findMatches(kind, tmdbId, titles, year, account).firstOrNull()

    /** Preserve every matching provider entry so the viewer can choose its version/category. */
    suspend fun findMatches(
        kind: XtreamKind,
        tmdbId: Int,
        titles: List<String>,
        year: String?,
        account: XtreamAccount = active,
    ): List<XtreamItem> {
        val index = matchIndex(kind, account)
        val wanted = titles.map { normalizeTitle(it) }.filter { it.length >= 2 }.toSet()
        val id = tmdbId.toString()
        val named = wanted.flatMap { index.byName[it].orEmpty() }.filter {
            (it.tmdb.isNullOrBlank() || it.tmdb == id) && (year == null || it.year == null || it.year == year)
        }
        return (index.byTmdb[id].orEmpty() + named).distinctBy { it.id }
            .sortedWith(compareBy<XtreamItem> { if (it.tmdb == id) 0 else 1 }
                .thenBy { if (year != null && (it.year == year || it.name.contains(year))) 0 else 1 }
                .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
    }

    /** An account's films or series by TMDB id and by cleaned-up name. */
    private class MatchIndex(val byTmdb: Map<String, List<XtreamItem>>, val byName: Map<String, List<XtreamItem>>)

    private val matchIndexes = mutableMapOf<String, MatchIndex>()
    private val matchLock = Mutex()

    /**
     * Built once per account and kind, off the main thread: cleaning tens of thousands of names
     * for every lookup froze TVs.
     */
    private suspend fun matchIndex(kind: XtreamKind, account: XtreamAccount): MatchIndex {
        val key = "${account.host}|${account.username}|$kind"
        matchLock.withLock { matchIndexes[key]?.let { return it } }
        val list = all(kind, account)
        val index = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            MatchIndex(
                list.filter { !it.tmdb.isNullOrBlank() }.groupBy { it.tmdb!! },
                list.groupBy { normalizeTitle(it.name) },
            )
        }
        matchLock.withLock { matchIndexes[key] = index }
        return index
    }

    suspend fun series(seriesId: String, account: XtreamAccount = active): XtreamSeries {
        val root = call(account, "get_series_info", mapOf("series_id" to seriesId)) as? JsonObject ?: throw notXtream()
        val info = root["info"] as? JsonObject
        // Episodes come as {"1": [...], "2": [...]} on most servers, a plain list on some.
        val groups: List<JsonElement> = when (val e = root["episodes"]) {
            is JsonObject -> e.values.flatMap { (it as? JsonArray).orEmpty() }
            is JsonArray -> e.flatMap { (it as? JsonArray) ?: listOf(it) }
            else -> emptyList()
        }
        val episodes = groups.mapNotNull { it as? JsonObject }.mapNotNull { ep ->
            val id = ep.text("id") ?: return@mapNotNull null
            val number = ep.text("episode_num")?.toIntOrNull() ?: 0
            XtreamEpisode(
                id = id,
                season = ep.text("season")?.toIntOrNull() ?: 1,
                number = number,
                title = ep.text("title") ?: "الحلقة $number",
                extension = ep.text("container_extension"),
                image = (ep["info"] as? JsonObject)?.text("movie_image")?.takeIf { it.startsWith("http") },
            )
        }.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number }))
        return XtreamSeries(
            name = info?.text("name").orEmpty(),
            plot = info?.text("plot"),
            cover = info?.text("cover")?.takeIf { it.startsWith("http") },
            episodes = episodes,
        )
    }

    /** Links to try in order for a channel or a film (live: HLS first, then MPEG-TS). */
    fun playUrls(item: XtreamItem, account: XtreamAccount = active): List<String> = if (account.id == FourU.account.id) listOfNotNull(item.directUrl) else when (item.kind) {
        XtreamKind.LIVE -> listOf(streamUrl(account, "live", "${item.id}.m3u8"), streamUrl(account, "live", "${item.id}.ts"))
        XtreamKind.MOVIE -> listOf(streamUrl(account, "movie", "${item.id}.${item.extension ?: "mp4"}"))
        XtreamKind.SERIES -> emptyList()
    }

    /** Links for something from the watch history, from the account it was watched on. */
    fun playUrls(entry: WatchEntry): List<String> {
        val a = settings.xtreamAccount(entry.accountId) ?: active
        return when (entry.kind) {
            WatchEntry.LIVE -> listOf(streamUrl(a, "live", "${entry.itemId}.m3u8"), streamUrl(a, "live", "${entry.itemId}.ts"))
            WatchEntry.MOVIE -> listOf(streamUrl(a, "movie", "${entry.itemId}.${entry.ext ?: "mp4"}"))
            else -> listOf(streamUrl(a, "series", "${entry.itemId}.${entry.ext ?: "mp4"}"))
        }
    }

    fun playUrls(episode: XtreamEpisode, account: XtreamAccount = active): List<String> =
        listOf(streamUrl(account, "series", "${episode.id}.${episode.extension ?: "mp4"}"))

    private fun streamUrl(a: XtreamAccount, type: String, file: String): String {
        return baseUrl(a).newBuilder()
            .addPathSegment(type)
            .addPathSegment(a.username)
            .addPathSegment(a.password)
            .addPathSegment(file)
            .build()
            .toString()
    }

    private fun baseUrl(account: XtreamAccount): HttpUrl =
        normalizeHost(account.host).toHttpUrlOrNull()
            ?: throw XtreamException("رابط الهوست غير صحيح، مثال: http://example.com:8080")

    private suspend fun call(account: XtreamAccount, action: String?, params: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            if (account.id == FourU.account.id) return@withContext FourU.liveApi(action, params)
            if (!account.isComplete) throw XtreamException("أضف حساب Xtream Codes من الإعدادات أول")
            val url = urlFor(account, action, params)

            val body = try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.code == 401 || response.code == 403) throw XtreamException("اسم المستخدم أو كلمة المرور غلط")
                    if (!response.isSuccessful) throw XtreamException("السيرفر رد بخطأ (${response.code})")
                    response.body?.string().orEmpty()
                }
            } catch (e: XtreamException) {
                throw e
            } catch (e: IOException) {
                throw XtreamException("ما قدرنا نوصل للسيرفر، تأكد من الهوست والإنترنت")
            }
            runCatching { json.parseToJsonElement(body) }.getOrNull() ?: throw notXtream()
        }

    private fun urlFor(account: XtreamAccount, action: String?, params: Map<String, String>): HttpUrl =
        baseUrl(account).newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
            .apply {
                action?.let { addQueryParameter("action", it) }
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun notXtream() = XtreamException("الرد مو من سيرفر Xtream Codes، تأكد من الهوست")

    private fun JsonObject.text(key: String): String? {
        val value: JsonElement = this[key] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** "example.com:8080/" → "http://example.com:8080", and drops a pasted "player_api.php". */
        fun normalizeHost(raw: String): String {
            var host = raw.trim().trimEnd('/')
            if (host.endsWith("player_api.php")) host = host.removeSuffix("player_api.php").trimEnd('/')
            if (!host.startsWith("http://", ignoreCase = true) && !host.startsWith("https://", ignoreCase = true)) {
                host = "http://$host"
            }
            return host
        }

        fun formatDate(date: Date): String = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(date)

        fun formatTime(date: Date): String = SimpleDateFormat("HH:mm", Locale.US).format(date)

        // Provider labels in front of the name: "[AR]", "|AR|", "AR:", "AR - ", "AR-AS-S:", "4K-AR|".
        private val tagPrefix = Regex("""^\s*(\[[^\]]*\]|\|[^|]{1,6}\||[A-Z0-9]{1,6}(-[A-Z0-9]{1,6}){1,4}\s*[|:]\s*|[A-Z]{2,3}\s*[|:]\s*|[A-Z]{2,3}\s+-\s+)""")
        private val bracketed = Regex("""[(\[{][^)\]}]*[)\]}]""")
        private val qualityWords = Regex("""\b(4k|8k|uhd|fhd|hd|sd|(?:360|480|576|720|1080|1440|2160|4320)p|hdr10\+?|hdr|hevc|[xh][ .]?26[45]|multi[- ]?sub|vip)\b""")
        private val yearWord = Regex("""\b(19|20)\d{2}\b""")
        private val nonWord = Regex("""[^\p{L}\p{Nd}]+""")

        /** "AR| The Batman (2022) 4K" → "the batman"; also folds Arabic letter variants. */
        fun normalizeTitle(raw: String): String {
            var t = raw
            repeat(2) { t = t.replace(tagPrefix, "") }
            t = t.lowercase(Locale.ROOT)
                .replace(bracketed, " ")
                .replace(qualityWords, " ")
                .replace(yearWord, " ")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ة', 'ه').replace('ى', 'ي')
                .replace(nonWord, " ")
                .trim()
            return t.replace(Regex("""\s+"""), " ")
        }

        private fun decodeBase64(value: String): String =
            runCatching { String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: value
    }
}
