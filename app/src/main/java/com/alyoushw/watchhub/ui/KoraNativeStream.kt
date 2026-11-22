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
        /** A media address read from a server page (its player config or HTML), sent with that page as referer. */
        fun fromPage(url: String, pageUrl: String): KoraNativeStream? {
            val host = url.toHttpUrlOrNull()?.host?.lowercase(java.util.Locale.ROOT) ?: return null
            if (AD_HOSTS.any { host.contains(it) }) return null
            val page = pageUrl.toHttpUrlOrNull() ?: return null
            return from(url, null, mapOf("Referer" to page.toString(), "Origin" to "${page.scheme}://${page.host}"))
        }
        internal val AD_HOSTS = listOf("doubleclick", "googlesyndication", "googleads", "adservice", "popads", "propeller",
            "adsterra", "exoclick", "juicyads", "trafficjunky", "adnxs", "imasdk", "vast", "onclick",
            "adskeeper", "mgid.com", "taboola", "outbrain", "adcash", "hilltopads", "clickadu", "a-ads.com", "monetag",
            "popcash", "richads", "galaksion", "admaven", "pubfuture", "bidvertiser", "highperformanceformat",
            "profitablecpmrate", "effectivegatecpm", "adspyglass", "realsrv", "pemsrv", "tsyndicate")

        /** An advert network request: the Kora tab drops it, like a browser ad blocker. */
        fun isAd(request: WebResourceRequest): Boolean {
            val host = request.url.host?.lowercase(java.util.Locale.ROOT) ?: return false
            return AD_HOSTS.any { host.contains(it) }
        }

        /**
         * A live stream reported by any HTTPS frame of the Kora site. The site changes its player's
         * address now and then, so any player counts; only HLS (how matches stream) and no advert hosts.
         */
        fun observed(url: String, mime: String, referer: String): KoraNativeStream? {
            val page = referer.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
            val host = url.toHttpUrlOrNull()?.host?.lowercase(java.util.Locale.ROOT) ?: return null
            if (AD_HOSTS.any { host.contains(it) || page.host.contains(it) }) return null
            return from(url, mime, mapOf("Origin" to "https://${page.host}", "Referer" to "https://${page.host}/"))
                ?.takeIf { it.transport == SourceTransport.HLS }
        }

        /** A live stream request from any frame of the Kora site (see [observed]). */
        fun liveFromAnyPlayer(request: WebResourceRequest): KoraNativeStream? =
            anySite(request)?.takeIf { it.transport == SourceTransport.HLS }

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
