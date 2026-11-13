package com.alyoushw.watchhub.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Loopback-only HTTP bridge: never exposes bytes until their torrent piece has been verified. */
internal class TorrentStreamServer(
    private val file: File,
    private val length: Long,
    private val fileOffset: Long,
    private val pieceLength: Int,
    private val verified: (Int) -> Boolean,
    private val prioritize: (Long, Long) -> Unit,
    private val failure: () -> Boolean = { false },
    private val onError: (Exception) -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "torrent-http").apply { isDaemon = true } }
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val path = "/${UUID.randomUUID()}/video.${file.extension}"
    val uri = "http://127.0.0.1:${listener.localPort}$path"
    private val acceptor = Thread({
        while (!closed.get()) {
            try {
                val socket = listener.accept()
                if (clients.size >= 4) { socket.close(); continue }
                clients += socket
                workers.execute { try { serve(socket) } catch (error: Exception) {
                    if (!closed.get() && error !is InterruptedException && error.message != "Reader disconnected") onError(error)
                } finally { clients -= socket; runCatching { socket.close() } } }
            } catch (_: Exception) { if (!closed.get()) close() }
        }
    }, "torrent-http-accept").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().buffered()
        fun line(): String {
            val data = StringBuilder()
            while (data.length < 8192) {
                val byte = input.read()
                if (byte == -1 || byte == 10) return data.toString().trimEnd('\r')
                data.append(byte.toChar())
            }
            throw IOException("Request header too large")
        }
        val request = line().split(' ')
        if (request.size < 3 || request[1] != path) { reply(socket, "404 Not Found"); return }
        if (request[0] !in setOf("GET", "HEAD")) { reply(socket, "405 Method Not Allowed"); return }
        var range: String? = null
        var headerBytes = 0
        while (true) {
            val header = line()
            headerBytes += header.length
            if (headerBytes > 16384) { reply(socket, "431 Request Header Fields Too Large"); return }
            if (header.isEmpty()) break
            if (header.startsWith("Range:", true)) range = header.substringAfter(':').trim()
        }
        val bytes = byteRange(range, length)
        if (bytes == null) { reply(socket, "416 Range Not Satisfiable", "Content-Range: bytes */$length\r\n"); return }
        val (start, end) = bytes
        val output = socket.getOutputStream().buffered(64 * 1024)
        val mime = if (file.extension.lowercase() in setOf("mkv", "webm")) "video/x-matroska" else "video/mp4"
        val headers = "HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\n" +
            "Content-Length: ${end - start + 1}\r\nContent-Type: $mime\r\nAccept-Ranges: bytes\r\nConnection: close\r\n" +
            (if (range != null) "Content-Range: bytes $start-$end/$length\r\n" else "") + "\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII)); output.flush()
        if (request[0] == "HEAD") return
        prioritize(start, minOf(end, start + 2 * 1024 * 1024 - 1))
        val buffer = ByteArray(64 * 1024)
        var position = start
        var nextPriority = start
        // A sparse/preallocated file may already have its final size. Only havePiece() establishes readable bytes.
        while (position <= end && !closed.get()) {
            val piece = ((fileOffset + position) / pieceLength).toInt()
            val deadline = System.nanoTime() + 120_000_000_000L
            if (position >= nextPriority) {
                prioritize(position, minOf(end, position + 2 * 1024 * 1024 - 1))
                nextPriority = position + 512 * 1024
            }
            var disconnectedCheck = System.nanoTime()
            while (!closed.get() && !verified(piece)) {
                if (System.nanoTime() - disconnectedCheck > 500_000_000L) {
                    socket.soTimeout = 1
                    try { if (input.read() == -1) throw IOException("Reader disconnected") }
                    catch (_: java.net.SocketTimeoutException) { }
                    disconnectedCheck = System.nanoTime()
                }
                if (failure() || System.nanoTime() > deadline) throw IOException("Torrent pieces unavailable")
                Thread.sleep(30)
            }
            if (closed.get()) break
            val boundary = ((piece.toLong() + 1) * pieceLength - fileOffset).coerceAtMost(end + 1)
            val count = minOf(buffer.size.toLong(), boundary - position).toInt()
            if (count <= 0) throw IOException("Invalid torrent piece boundary")
            var read = 0
            while (!closed.get()) {
                if (file.isFile) {
                    RandomAccessFile(file, "r").use { disk -> disk.seek(position); read = disk.read(buffer, 0, count) }
                    if (read > 0) break
                }
                if (System.nanoTime() > deadline || failure()) throw IOException("Torrent storage unavailable")
                Thread.sleep(30)
            }
            if (read <= 0) break
            output.write(buffer, 0, read); output.flush()
            position += read
        }
    }

    private fun reply(socket: Socket, status: String, extra: String = "") {
        socket.getOutputStream().write("HTTP/1.1 $status\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    companion object {
        internal fun byteRange(header: String?, length: Long): Pair<Long, Long>? {
            if (length <= 0) return null
            if (header == null) return 0L to length - 1
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header) ?: return null
            val first = match.groupValues[1]
            val last = match.groupValues[2]
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return (length - suffix).coerceAtLeast(0) to length - 1
            }
            val start = first.toLongOrNull()?.takeIf { it in 0 until length } ?: return null
            val end = if (last.isEmpty()) length - 1 else last.toLongOrNull()?.coerceAtMost(length - 1) ?: return null
            return (start to end).takeIf { end >= start }
        }
    }
}
