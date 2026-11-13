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
            val path = url.encodedPath.lowercase(java.util.Locale.ROOT)
            val (type, mime) = when {
                path.endsWith(".m3u8") || path.endsWith("/master.txt") -> SourceTransport.HLS to MimeTypes.APPLICATION_M3U8
                path.endsWith(".mp4") -> SourceTransport.MP4 to MimeTypes.VIDEO_MP4
                path.endsWith(".mkv") -> SourceTransport.MKV to MimeTypes.VIDEO_MATROSKA
                else -> return null
            }
            val headers = request.requestHeaders.filterKeys { name ->
                name.lowercase(java.util.Locale.ROOT) in setOf("user-agent", "referer", "origin", "cookie", "authorization")
            }
            return KoraNativeStream(url.toString(), type, mime, headers)
        }
        private const val PLAYER_HOST = "player.majedjs.site"
    }
}
