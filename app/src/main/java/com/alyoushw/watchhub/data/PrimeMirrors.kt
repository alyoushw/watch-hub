package com.alyoushw.watchhub.data

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The same TMDB-addressed alternative provider used by Prime's client. This decodes its
 * normal API envelope, not video DRM. Session headers and URLs never enter saved history. */
internal object PrimeMirrors {
    private val json = Json { ignoreUnknownKeys = true }
    // Resolving an uncached episode can take longer than probing an already-known media URL.
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    private val probe = StreamProbe(http.newBuilder().readTimeout(12, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build())
    private val random = SecureRandom()

    suspend fun resolve(base: String, query: SourceQuery, responseSalt: String, userAgent: String): PrimeStream? = coroutineScope {
        val host = base.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@coroutineScope null
        val origin = "https://${host.host.removePrefix("new.")}" + if (host.port != 443) ":${host.port}" else ""
        val page = "$origin/"
        val path = if (query.type == MediaType.MOVIE) "movie/${query.tmdbId}" else "tv/${query.tmdbId}/${query.season ?: 1}/${query.episode ?: 1}"
        val found = Channel<PrimeStream>(Channel.UNLIMITED)
        // These are the five provider names advertised by the original Prime client.
        val jobs = listOf("flixhq", "yflix", "rogflix", "ophim", "onehd").map { provider -> launch {
            try {
                val nonce = ByteArray(16).also(random::nextBytes)
                val url = host.newBuilder().addPathSegment(provider).addPathSegments(path)
                    .addQueryParameter("_n", Base64.encodeToString(nonce, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).build()
                val headers = mapOf("User-Agent" to userAgent, "Referer" to page, "Origin" to origin, "Accept" to "*/*")
                val envelope = fetch(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build())
                val root = decode(envelope, responseSalt)
                val rows = ((root["streams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } + root)
                    .filter { it.text("url").isNotBlank() && it.text("drm_scheme") in listOf("", "none", "null") }
                    .sortedByDescending { Regex("\\d{3,4}").find(it.text("quality"))?.value?.toIntOrNull() ?: 0 }
                for (row in rows) {
                    val streamUrl = host.resolve(row.text("url"))?.takeIf { it.isHttps } ?: continue
                    val type = when {
                        row.text("type").equals("mp4", true) || streamUrl.encodedPath.endsWith(".mp4", true) -> SourceTransport.MP4
                        streamUrl.encodedPath.endsWith(".mkv", true) -> SourceTransport.MKV
                        row.text("type").equals("hls", true) || streamUrl.encodedPath.contains(".m3u8", true) -> SourceTransport.HLS
                        else -> continue
                    }
                    val streamHeaders = buildMap {
                        putAll(headers.filterKeys { it != "Accept" })
                        for (o in listOfNotNull(root["headers"] as? JsonObject, row["headers"] as? JsonObject)) {
                            o.forEach { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { put(name, it) } }
                        }
                    }
                    val variant = SourceVariant("prime-$provider", "Prime", streamUrl.toString(), type, streamHeaders)
                    if (probe.compatible(variant)) {
                        found.send(PrimeStream(variant.url, streamHeaders, when (type) {
                            SourceTransport.HLS -> "application/x-mpegURL"
                            SourceTransport.MKV -> "video/x-matroska"
                            else -> "video/mp4"
                        }))
                        break
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* An unavailable provider does not hide a title on another one. */ }
        } }
        val completion = launch { jobs.joinAll(); found.close() }
        try { found.receiveCatching().getOrNull() }
        finally { jobs.forEach { it.cancel() }; completion.cancel(); found.cancel() }
    }

    private suspend fun fetch(request: Request): JsonObject = suspendCancellableCoroutine { pending ->
        val call = http.newCall(request)
        pending.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (pending.isActive) pending.resumeWithException(SourceException("تعذّر الاتصال بمزوّد Prime البديل"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val root = response.use {
                        if (!it.isSuccessful) throw SourceException("مزوّد Prime البديل غير متاح")
                        val source = it.body?.source() ?: throw SourceException("رد Prime فارغ")
                        if (source.request(2 * 1024 * 1024L + 1)) throw SourceException("رد Prime أكبر من الحد المسموح")
                        json.parseToJsonElement(source.readUtf8()).jsonObject
                    }
                    if (pending.isActive) pending.resume(root)
                } catch (e: Exception) { if (pending.isActive) pending.resumeWithException(e) }
            }
        })
    }

    private fun decode(root: JsonObject, salt: String): JsonObject {
        if ((root["v"] as? JsonPrimitive)?.intOrNull != 2 || root["d"] == null || root["n"] == null) return root
        val key = MessageDigest.getInstance("SHA-256").digest((root.text("n") + salt).toByteArray(Charsets.UTF_8))
        val bytes = Base64.decode(root.text("d"), Base64.URL_SAFE or Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return json.parseToJsonElement(cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)).jsonObject
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
