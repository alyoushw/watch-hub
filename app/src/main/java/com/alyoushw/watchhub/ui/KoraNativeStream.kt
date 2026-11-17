package com.alyoushw.watchhub.ui

import android.webkit.WebResourceRequest
import androidx.media3.common.MimeTypes
import com.alyoushw.watchhub.data.SourceTransport
import com.alyoushw.watchhub.data.SourceVariant
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Observe only real media requests from the known Kora player, after normal web authorization. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal data class KoraNativeStream(val url: String, val transport: SourceTransport, val mimeType: String,
    val headers: Map<String, String>) {
    fun variant() = SourceVariant("kora-session", "البث المباشر", url, transport, headers)

    companion object {
        fun from(request: WebResourceRequest): KoraNativeStream? {
            if (request.method != "GET") return null
            val url = request.url.toString().toHttpUrlOrNull() ?: return null
            fun header(name: String) = request.requestHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value
            val origin = header("Origin")?.toHttpUrlOrNull()?.host
            val referer = header("Referer")?.toHttpUrlOrNull()?.host
            if (origin != PLAYER_HOST && referer != PLAYER_HOST && url.host != PLAYER_HOST) return null
            return from(url.toString(), null, request.requestHeaders)
        }

        /**
         * Any site's video request (the TukTuk browser): the user picks it with a button, never
         * auto-played, so a stray advert clip cannot take over. Known advert networks are skipped.
         */
        fun anySite(request: WebResourceRequest): KoraNativeStream? {
            if (request.method != "GET") return null
            val host = request.url.host?.lowercase(java.util.Locale.ROOT) ?: return null
            if (AD_HOSTS.any { host.contains(it) }) return null
            return from(request.url.toString(), null, request.requestHeaders)
        }
        private val AD_HOSTS = listOf("doubleclick", "googlesyndication", "googleads", "adservice", "popads", "propeller",
            "adsterra", "exoclick", "juicyads", "trafficjunky", "adnxs", "imasdk", "vast", "onclick")

        /** Called only by the message listener restricted to the HTTPS player origin. */
        fun observed(url: String, mime: String, referer: String): KoraNativeStream? {
            if (referer.toHttpUrlOrNull()?.let { it.isHttps && it.host == PLAYER_HOST } != true) return null
            return from(url, mime, mapOf("Origin" to "https://$PLAYER_HOST", "Referer" to "https://$PLAYER_HOST/"))
        }

        private fun from(value: String, mimeType: String?, requestHeaders: Map<String, String>): KoraNativeStream? {
            val url = value.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } ?: return null
            val path = url.encodedPath.lowercase(java.util.Locale.ROOT)
            val contentType = mimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
            val (type, mime) = when {
                path.endsWith(".m3u8") || path.endsWith("/master.txt") || contentType in setOf(
                    "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl") -> SourceTransport.HLS to MimeTypes.APPLICATION_M3U8
                path.endsWith(".mp4") || contentType == "video/mp4" -> SourceTransport.MP4 to MimeTypes.VIDEO_MP4
                path.endsWith(".mkv") || contentType == "video/x-matroska" -> SourceTransport.MKV to MimeTypes.VIDEO_MATROSKA
                else -> return null
            }
            val headers = requestHeaders.filterKeys { name ->
                name.lowercase(java.util.Locale.ROOT) in setOf("user-agent", "referer", "origin", "cookie", "authorization")
            }
            return KoraNativeStream(url.toString(), type, mime, headers)
        }
        const val PLAYER_HOST = "player.majedjs.site"
    }
}
