package com.alyoushw.watchhub.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class YouTubeException(message: String) : Exception(message)

data class YtVideo(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String?,
    val thumbnail: String?,
    val publishedAt: Long?,
    /** Length in seconds, when known. */
    val duration: Long? = null,
)

data class YtPlaylist(val id: String, val title: String, val thumbnail: String?, val count: Int?)

/** Code to show while linking: open [verificationUrl] and type [userCode]. */
data class YtLinkCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val interval: Int, val expiresIn: Int)

/**
 * The user's YouTube (read-only), with their own Google account: subscriptions, playlists,
 * liked videos, popular and search. Linking runs through the user's Watch Hub site (Google's
 * "enter a code" sign-in, the client secret stays on the server); the refresh token stays on
 * this device. Video data comes straight from the YouTube Data API.
 */
class YouTube(context: Context, private val settings: SettingsStore) {
    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private val _linked = MutableStateFlow(refreshToken != null)

    /** Whether a YouTube account is linked on this device (the YouTube tab shows then). */
    val linked: StateFlow<Boolean> = _linked.asStateFlow()

    private val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)
    private var accessToken: String? = null
    private var accessUntil = 0L
    private val tokenLock = Mutex()

    // ---------- linking ----------

    suspend fun startLink(): YtLinkCode {
        val o = post("/yt/device/start", "{}")
        o["error"]?.let { throw YouTubeException(linkError(it.jsonPrimitive.contentOrNull)) }
        return YtLinkCode(
            deviceCode = o.str("deviceCode") ?: throw YouTubeException("ما وصلنا رد من موقعك"),
            userCode = o.str("userCode").orEmpty(),
            verificationUrl = o.str("verificationUrl") ?: "https://www.google.com/device",
            interval = o["interval"]?.jsonPrimitive?.intOrNull ?: 5,
            expiresIn = o["expiresIn"]?.jsonPrimitive?.intOrNull ?: 1800,
        )
    }

    /** Waits until the user approves [code] (true), refuses or lets it expire (false). */
    suspend fun finishLink(code: YtLinkCode): Boolean {
        var wait = code.interval.coerceAtLeast(3) * 1000L
        val until = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(wait)
            val o = runCatching { post("/yt/device/poll", """{"deviceCode":"${code.deviceCode}"}""") }.getOrNull() ?: continue
            when (o.str("status")) {
                "ok" -> {
                    val refresh = o.str("refreshToken") ?: return false
                    prefs.edit().putString(KEY_REFRESH, refresh).apply()
                    accessToken = o.str("accessToken")
                    accessUntil = System.currentTimeMillis() + ((o["expiresIn"]?.jsonPrimitive?.longOrNull ?: 3600) - 120) * 1000
                    _linked.value = true
                    return true
                }
                "slow_down" -> wait += 2000
                "pending" -> Unit
                else -> return false
            }
        }
        return false
    }

    fun unlink() {
        prefs.edit().clear().apply()
        accessToken = null
        accessUntil = 0
        feedCache = null
        _linked.value = false
    }

    private fun linkError(code: String?) = when (code) {
        "not_configured" -> "يوتيوب غير مفعّل في موقعك (GOOGLE_TV_CLIENT_ID)"
        "invalid_scope" -> "فعّل «YouTube Data API v3» في Google Cloud أول"
        else -> "ما قدرنا نبدأ الربط ($code)"
    }

    private suspend fun token(): String = tokenLock.withLock {
        accessToken?.takeIf { System.currentTimeMillis() < accessUntil }?.let { return it }
        val refresh = refreshToken ?: throw YouTubeException("اربط حساب يوتيوب من الإعدادات")
        val o = post("/yt/refresh", """{"refreshToken":"$refresh"}""")
        val t = o.str("accessToken")
        if (t == null) {
            if (o.str("error") == "invalid_grant") {
                unlink()
                throw YouTubeException("انتهى ربط يوتيوب، اربطه مرة ثانية من الإعدادات")
            }
            throw YouTubeException("ما قدرنا نجدد الدخول ليوتيوب")
        }
        accessToken = t
        accessUntil = System.currentTimeMillis() + ((o["expiresIn"]?.jsonPrimitive?.longOrNull ?: 3600) - 120) * 1000
        t
    }

    // ---------- lists ----------

    private var feedCache: Pair<Long, List<YtVideo>>? = null

    /** Latest uploads of the channels the user subscribes to, newest first (kept 20 minutes). */
    suspend fun subscriptionsFeed(force: Boolean = false): List<YtVideo> {
        feedCache?.takeIf { !force && System.currentTimeMillis() - it.first < 20 * 60_000 }?.let { return it.second }
        // Up to 100 subscriptions, then each channel's uploads playlist (UC… → UU…).
        val channels = mutableListOf<String>()
        var page: String? = null
        for (i in 0 until 2) {
            val o = api("subscriptions", "part" to "snippet", "mine" to "true", "maxResults" to "50", "order" to "relevance", "pageToken" to page)
            o["items"]?.jsonArray?.forEach { item ->
                item.jsonObject["snippet"]?.jsonObject?.get("resourceId")?.jsonObject?.str("channelId")?.let(channels::add)
            }
            page = o.str("nextPageToken") ?: break
        }
        val videos = coroutineScope {
            channels.distinct().take(60).map { ch ->
                async {
                    runCatching {
                        val o = api("playlistItems", "part" to "snippet,contentDetails", "playlistId" to "UU" + ch.removePrefix("UC"), "maxResults" to "4")
                        o["items"]?.jsonArray.orEmpty().mapNotNull { playlistVideo(it.jsonObject) }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        val list = withDurations(videos.sortedByDescending { it.publishedAt ?: 0 }.take(120))
        feedCache = System.currentTimeMillis() to list
        return list
    }

    suspend fun popular(): List<YtVideo> {
        val region = settings.region.value.ifBlank { "SA" }
        val o = api("videos", "part" to "snippet,contentDetails", "chart" to "mostPopular", "regionCode" to region, "maxResults" to "40")
        return o["items"]?.jsonArray.orEmpty().mapNotNull { videoOf(it.jsonObject) }
    }

    suspend fun liked(): List<YtVideo> {
        val o = api("videos", "part" to "snippet,contentDetails", "myRating" to "like", "maxResults" to "50")
        return o["items"]?.jsonArray.orEmpty().mapNotNull { videoOf(it.jsonObject) }
    }

    suspend fun playlists(): List<YtPlaylist> {
        val o = api("playlists", "part" to "snippet,contentDetails", "mine" to "true", "maxResults" to "50")
        return o["items"]?.jsonArray.orEmpty().map { it.jsonObject }.map { p ->
            val sn = p["snippet"]?.jsonObject
            YtPlaylist(
                id = p.str("id").orEmpty(),
                title = sn?.str("title").orEmpty(),
                thumbnail = sn?.thumb(),
                count = p["contentDetails"]?.jsonObject?.get("itemCount")?.jsonPrimitive?.intOrNull,
            )
        }
    }

    suspend fun playlist(id: String): List<YtVideo> {
        val o = api("playlistItems", "part" to "snippet,contentDetails", "playlistId" to id, "maxResults" to "50")
        return withDurations(o["items"]?.jsonArray.orEmpty().mapNotNull { playlistVideo(it.jsonObject) })
    }

    /** Search costs 100 of the day's 10 000 units: only on submit, never while typing. */
    suspend fun search(q: String): List<YtVideo> {
        val o = api("search", "part" to "snippet", "q" to q, "type" to "video", "maxResults" to "30", "relevanceLanguage" to "ar")
        val list = o["items"]?.jsonArray.orEmpty().mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonObject?.str("videoId") ?: return@mapNotNull null
            val sn = obj["snippet"]?.jsonObject ?: return@mapNotNull null
            YtVideo(id, sn.str("title").orEmpty().unescape(), sn.str("channelTitle").orEmpty(), sn.str("channelId"), sn.thumb(), sn.time("publishedAt"))
        }
        return withDurations(list)
    }

    // ---------- helpers ----------

    private suspend fun withDurations(list: List<YtVideo>): List<YtVideo> {
        if (list.isEmpty()) return list
        val durations = mutableMapOf<String, Long>()
        list.map { it.id }.distinct().chunked(50).forEach { ids ->
            runCatching {
                val o = api("videos", "part" to "contentDetails", "id" to ids.joinToString(","), "maxResults" to "50")
                o["items"]?.jsonArray?.forEach { v ->
                    val obj = v.jsonObject
                    val d = obj["contentDetails"]?.jsonObject?.str("duration")?.let(::isoSeconds)
                    if (d != null) durations[obj.str("id").orEmpty()] = d
                }
            }
        }
        return list.map { it.copy(duration = durations[it.id] ?: it.duration) }
    }

    private fun playlistVideo(o: JsonObject): YtVideo? {
        val sn = o["snippet"]?.jsonObject ?: return null
        val id = o["contentDetails"]?.jsonObject?.str("videoId") ?: sn["resourceId"]?.jsonObject?.str("videoId") ?: return null
        val title = sn.str("title").orEmpty()
        if (title == "Private video" || title == "Deleted video") return null
        return YtVideo(
            id = id,
            title = title,
            channel = sn.str("videoOwnerChannelTitle") ?: sn.str("channelTitle").orEmpty(),
            channelId = sn.str("videoOwnerChannelId") ?: sn.str("channelId"),
            thumbnail = sn.thumb(),
            publishedAt = o["contentDetails"]?.jsonObject?.time("videoPublishedAt") ?: sn.time("publishedAt"),
        )
    }

    private fun videoOf(o: JsonObject): YtVideo? {
        val sn = o["snippet"]?.jsonObject ?: return null
        return YtVideo(
            id = o.str("id") ?: return null,
            title = sn.str("title").orEmpty(),
            channel = sn.str("channelTitle").orEmpty(),
            channelId = sn.str("channelId"),
            thumbnail = sn.thumb(),
            publishedAt = sn.time("publishedAt"),
            duration = o["contentDetails"]?.jsonObject?.str("duration")?.let(::isoSeconds),
        )
    }

    private suspend fun api(path: String, vararg query: Pair<String, String?>): JsonObject = withContext(Dispatchers.IO) {
        val url = "https://www.googleapis.com/youtube/v3/$path".toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()
        val t = token()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $t").build()
        try {
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                if (!res.isSuccessful) {
                    val reason = o["error"]?.jsonObject?.get("errors")?.jsonArray?.firstOrNull()?.jsonObject?.str("reason")
                    throw YouTubeException(
                        when {
                            res.code == 401 -> {
                                accessToken = null
                                "انتهى الدخول ليوتيوب، حاول مرة ثانية"
                            }
                            reason == "quotaExceeded" -> "خلص حد يوتيوب اليومي، يرجع بكرة"
                            reason == "accessNotConfigured" || reason == "SERVICE_DISABLED" -> "فعّل «YouTube Data API v3» في Google Cloud"
                            else -> "يوتيوب ما رد ($reason)"
                        },
                    )
                }
                o
            }
        } catch (e: java.io.IOException) {
            throw YouTubeException("ما قدرنا نوصل ليوتيوب، تأكد من الإنترنت")
        }
    }

    private suspend fun post(path: String, body: String): JsonObject = withContext(Dispatchers.IO) {
        val url = settings.webUrl.value.trimEnd('/') + path
        try {
            http.newCall(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: throw YouTubeException("موقعك ما رد (${res.code})، يمكن لسا يصحى، جرّب بعد دقيقة")
            }
        } catch (e: java.io.IOException) {
            throw YouTubeException("ما قدرنا نوصل لموقعك، تأكد من الإنترنت")
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonObject.time(key: String): Long? = str(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun JsonObject.thumb(): String? {
        val t = this["thumbnails"]?.jsonObject ?: return null
        return listOf("high", "medium", "standard", "default").firstNotNullOfOrNull { t[it]?.jsonObject?.str("url") }
    }

    private fun isoSeconds(iso: String): Long? = runCatching { Duration.parse(iso).seconds }.getOrNull()

    private fun String.unescape() = replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    companion object {
        private const val KEY_REFRESH = "refresh_token"
    }
}
