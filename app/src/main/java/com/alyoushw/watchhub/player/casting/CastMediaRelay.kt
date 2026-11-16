package com.alyoushw.watchhub.player.casting

import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class CastSource(val url: String, val mime: String, val title: String, val live: Boolean,
    val positionMs: Long, val durationMs: Long, val headers: Map<String, String>)

/** One media session, bound to Wi-Fi only. Opaque URLs never expose provider credentials. */
internal class CastMediaRelay(lan: CastLan, private val source: CastSource, private val flatHls: Boolean = false) : NanoHTTPD(lan.address.hostAddress, 0), Closeable {
    private data class Resource(val url: HttpUrl, val mime: String?)
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
    private val resources = java.util.LinkedHashMap<String, Resource>(64, 0.75f, true)
    private val badGateway = object : Response.IStatus {
        override fun getRequestStatus() = 502
        override fun getDescription() = "502 Bad Gateway"
    }
    private val keys = mutableMapOf<String, String>()
    private var nextId = 0L
    private val file = android.net.Uri.parse(source.url).takeIf { it.scheme == "file" }?.path?.let(::File)
    private val root = source.url.toHttpUrlOrNull() ?: file?.let { "http://127.0.0.1/local-media".toHttpUrlOrNull() }
        ?: error("هذا المصدر لا يدعم البث إلى جهاز آخر.")
    private val http = com.alyoushw.watchhub.data.Net.streams.newBuilder().callTimeout(0, TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher()).readTimeout(30, TimeUnit.SECONDS).addNetworkInterceptor { chain ->
            val request = chain.request()
            chain.proceed(request.newBuilder().apply {
                source.headers.forEach { (name, value) ->
                    if (request.url.host == root.host || name.equals("User-Agent", true)) header(name, value)
                }
            }.build())
        }.build()
    private val host = lan.address.hostAddress
    /** Diagnostics: how many requests the renderer made and the last upstream HTTP status. */
    @Volatile var hits = 0
    @Volatile var lastStatus = 0
    val mediaUrl: String get() = if (flatHls) "http://$host:$listeningPort/$secret/stream.ts" else register(root, source.mime)
    init { resources["0"] = Resource(root, source.mime); keys[root.toString()] = "0" }

    @Synchronized private fun register(url: HttpUrl, mime: String? = null): String {
        val id = keys[url.toString()] ?: (++nextId).toString(36).also { id ->
            resources[id] = Resource(url, mime)
            keys[url.toString()] = id
            if (resources.size > 4096) {
                val first = resources.entries.first { it.key != "0" }
                resources.remove(first.key)
                keys.remove(first.value.url.toString())
            }
        }
        if (mime != null && resources[id]?.mime == null) resources[id]?.let { resources[id] = it.copy(mime = mime) }
        val suffix = when {
            mime?.contains("mpegurl", true) == true || url.encodedPath.endsWith(".m3u8", true) -> ".m3u8"
            mime == "video/mp4" || url.encodedPath.endsWith(".mp4", true) -> ".mp4"
            mime == "video/x-matroska" || url.encodedPath.endsWith(".mkv", true) -> ".mkv"
            mime == "video/mp2t" || url.encodedPath.endsWith(".ts", true) -> ".ts"
            else -> ""
        }
        return "http://$host:$listeningPort/$secret/$id$suffix"
    }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.split('/').filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[0] != secret) return text(Response.Status.NOT_FOUND)
        hits++
        if (flatHls && parts[1] == "stream.ts") {
            if (session.method !in setOf(Method.GET, Method.HEAD)) return text(Response.Status.METHOD_NOT_ALLOWED)
            val body = if (session.method == Method.HEAD) ByteArrayInputStream(byteArrayOf()) else HlsStream(if (source.live) -1 else source.positionMs)
            return cors(newChunkedResponse(Response.Status.OK, "video/mp2t", body)).apply {
                addHeader("transferMode.dlna.org", "Streaming")
                addHeader("contentFeatures.dlna.org", DLNA_FEATURES.replace("OP=01", "OP=00"))
            }
        }
        val resource = synchronized(this) { resources[parts[1].substringBefore('.')] } ?: return text(Response.Status.NOT_FOUND)
        if (session.method == Method.OPTIONS) return text(Response.Status.OK)
        if (session.method !in setOf(Method.GET, Method.HEAD)) return text(Response.Status.METHOD_NOT_ALLOWED)
        return try {
            if (file != null && resource.url == root) return serveFile(session, file)
            val upstream = resource.url.newBuilder().apply {
                for (key in listOf("_HLS_msn", "_HLS_part", "_HLS_skip")) {
                    session.parameters[key]?.firstOrNull()?.let { setQueryParameter(key, it) }
                }
            }.build()
            val request = Request.Builder().url(upstream).apply {
                if (resource.mime?.contains("mpegurl", true) != true && !resource.url.encodedPath.endsWith(".m3u8", true))
                    session.headers["range"]?.let { header("Range", it) }
            }.build()
            val response = http.newCall(request).execute()
            lastStatus = response.code
            val body = response.body
            val status = Response.Status.lookup(response.code) ?: badGateway
            if (!response.isSuccessful || body == null) {
                val range = response.header("Content-Range")
                response.close()
                return text(status).apply { range?.let { addHeader("Content-Range", it) } }
            }
            val mime = response.header("Content-Type")?.substringBefore(';')?.takeIf { it != "application/octet-stream" }
                ?: resource.mime ?: "application/octet-stream"
            val hls = resource.mime?.contains("mpegurl", true) == true || mime.contains("mpegurl", true) ||
                response.request.url.encodedPath.endsWith(".m3u8", true) ||
                ((mime.startsWith("text/") || mime == "application/octet-stream") && body.source().peek().let { peek ->
                    peek.request(16)
                    peek.readByteArray(minOf(16L, peek.buffer.size)).toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")
                })
            if (hls) {
                response.use {
                    val input = body.source()
                    if (input.request(2L * 1024 * 1024 + 1)) return text(badGateway)
                    val playlist = input.readUtf8().removePrefix("\uFEFF")
                    if (!playlist.trimStart().startsWith("#EXTM3U")) return text(badGateway)
                    val base = response.request.url
                    val master = playlist.contains("#EXT-X-STREAM-INF:")
                    val rewritten = playlist.lineSequence().joinToString("\n") { line ->
                        if (line.isBlank()) line
                        else if (!line.startsWith('#')) register(base.resolve(line.trim()) ?: error("Invalid playlist URI"),
                            if (master) "application/vnd.apple.mpegurl" else null)
                        else Regex("""URI="([^"]+)"""").replace(line) { match ->
                            val url = base.resolve(match.groupValues[1]) ?: return@replace match.value
                            val playlistUri = line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") || line.startsWith("#EXT-X-RENDITION-REPORT:")
                            "URI=\"${register(url, if (playlistUri) "application/vnd.apple.mpegurl" else null)}\""
                        }
                    }.toByteArray(Charsets.UTF_8)
                    return cors(newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", ByteArrayInputStream(rewritten), rewritten.size.toLong()))
                }
            }
            val input = object : FilterInputStream(body.byteStream()) {
                override fun close() { try { super.close() } finally { response.close() } }
            }
            val length = if (session.method == Method.HEAD) response.header("Content-Length")?.toLongOrNull() ?: body.contentLength() else body.contentLength()
            val result = if (length >= 0) newFixedLengthResponse(status, mime, input, length) else newChunkedResponse(status, mime, input)
            for (name in listOf("Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) response.header(name)?.let { result.addHeader(name, it) }
            result.addHeader("transferMode.dlna.org", "Streaming")
            result.addHeader("contentFeatures.dlna.org", DLNA_FEATURES)
            cors(result)
        } catch (_: Exception) { if (lastStatus == 0) lastStatus = -1; text(badGateway) }
    }

    private fun serveFile(session: IHTTPSession, file: File): Response {
        val length = file.length()
        if (!file.isFile || length <= 0) return text(Response.Status.NOT_FOUND)
        val range = session.headers["range"]
        val match = range?.let { Regex("bytes=(\\d*)-(\\d*)").matchEntire(it) }
        val start: Long
        val end: Long
        if (range == null) { start = 0; end = length - 1 }
        else if (match != null && match.groupValues[1].isEmpty()) {
            val suffix = match.groupValues[2].toLongOrNull() ?: 0
            start = if (suffix > 0) (length - suffix).coerceAtLeast(0) else length
            end = length - 1
        } else {
            start = match?.groupValues?.get(1)?.toLongOrNull() ?: length
            end = match?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
        }
        if (start !in 0 until length || end < start) return text(Response.Status.RANGE_NOT_SATISFIABLE)
            .apply { addHeader("Content-Range", "bytes */$length") }
        val input = if (session.method == Method.HEAD) ByteArrayInputStream(byteArrayOf()) else object : InputStream() {
            private val handle = RandomAccessFile(file, "r").apply { seek(start) }
            private var left = end - start + 1
            override fun read(): Int { if (left <= 0) return -1; val value = handle.read(); if (value >= 0) left--; return value }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (left <= 0) return -1
                val read = handle.read(bytes, offset, minOf(count.toLong(), left).toInt())
                if (read > 0) left -= read
                return read
            }
            override fun close() { handle.close() }
        }
        return cors(newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
            source.mime, input, end - start + 1)).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("transferMode.dlna.org", "Streaming")
            addHeader("contentFeatures.dlna.org", DLNA_FEATURES)
            if (range != null) addHeader("Content-Range", "bytes $start-$end/$length")
        }
    }

    /**
     * DLNA renderers rarely understand HLS playlists, so the segments are joined into one continuous
     * MPEG-TS (or fMP4) stream that starts at [startMs]; live playlists are followed as they grow.
     */
    private inner class HlsStream(private val startMs: Long) : InputStream() {
        private val queue = ArrayDeque<HttpUrl>()
        private var current: InputStream? = null
        private var response: okhttp3.Response? = null
        private var media: HttpUrl? = null
        private var lastSequence = -1L
        private var initDone = false
        private var ended = false
        private var waitMs = 2000L
        @Volatile private var closed = false

        private fun fetch(url: HttpUrl): String = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            lastStatus = r.code
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            r.body?.string()?.removePrefix("\uFEFF") ?: ""
        }

        private fun refill() {
            var url = media ?: root
            var text = fetch(url)
            if (media == null && text.contains("#EXT-X-STREAM-INF:")) {
                // Pick the best variant; the TV has the bandwidth and screen for it.
                val lines = text.lines()
                val best = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:") }.maxByOrNull { i ->
                    Regex("""BANDWIDTH=(\d+)""").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                }?.let { i -> lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') } } ?: throw java.io.IOException("Empty HLS master")
                url = url.resolve(best.trim()) ?: throw java.io.IOException("Bad variant")
                text = fetch(url)
            }
            media = url
            var sequence = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            Regex("""#EXT-X-TARGETDURATION:(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { waitMs = (it * 500).coerceIn(1000, 6000) }
            ended = text.contains("#EXT-X-ENDLIST")
            val segments = mutableListOf<Triple<Long, Double, HttpUrl>>()
            var duration = 0.0
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-KEY:") && !line.contains("METHOD=NONE") -> throw java.io.IOException("Encrypted HLS")
                    line.startsWith("#EXT-X-MAP:") && !initDone -> Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                        ?.let { url.resolve(it) }?.let { queue.addLast(it); initDone = true }
                    line.startsWith("#EXTINF:") -> duration = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                    line.isNotEmpty() && !line.startsWith('#') -> { url.resolve(line)?.let { segments += Triple(sequence, duration, it) }; sequence++ }
                }
            }
            if (lastSequence < 0) {
                // First fill: VOD starts at the watch position, live starts near the edge.
                var skip = if (startMs < 0 || !ended) (segments.size - 3).coerceAtLeast(0) else 0
                if (startMs > 0 && ended) {
                    var elapsed = 0.0
                    skip = segments.indexOfFirst { elapsed += it.second; elapsed * 1000 > startMs }.coerceAtLeast(0)
                }
                segments.drop(skip).forEach { queue.addLast(it.third); lastSequence = it.first }
                if (lastSequence < 0) lastSequence = sequence - 1
            } else segments.filter { it.first > lastSequence }.forEach { queue.addLast(it.third); lastSequence = it.first }
        }

        private fun next(): InputStream? {
            while (!closed) {
                if (queue.isEmpty()) {
                    if (ended && lastSequence >= 0) return null
                    if (lastSequence >= 0) Thread.sleep(waitMs)
                    refill()
                    continue
                }
                val r = http.newCall(Request.Builder().url(queue.removeFirst()).build()).execute()
                if (!r.isSuccessful || r.body == null) { r.close(); throw java.io.IOException("Segment HTTP ${r.code}") }
                response = r
                return r.body!!.byteStream()
            }
            return null
        }

        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            while (!closed) {
                val input = current ?: next()?.also { current = it } ?: return -1
                val read = input.read(bytes, offset, count)
                if (read >= 0) return read
                response?.close(); response = null; current = null
            }
            return -1
        }
        override fun close() { closed = true; response?.close(); response = null; current = null }
    }

    private fun text(status: Response.IStatus) = cors(newFixedLengthResponse(status, "text/plain", status.description))
    private fun cors(response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
        addHeader("Access-Control-Allow-Private-Network", "true")
        addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
        addHeader("Cache-Control", "no-store")
    }
    override fun useGzipWhenAccepted(response: Response) = false
    override fun close() { stop(); synchronized(this) { resources.clear(); keys.clear() }; http.dispatcher.cancelAll() }
}
