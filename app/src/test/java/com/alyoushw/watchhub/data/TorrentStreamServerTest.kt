package com.alyoushw.watchhub.data

import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TorrentStreamServerTest {
    @Test fun rangesHandleSeekTailAndInvalidRequests() {
        assertEquals(10L to 19L, TorrentStreamServer.byteRange("bytes=10-19", 100))
        assertEquals(90L to 99L, TorrentStreamServer.byteRange("bytes=-10", 100))
        assertEquals(10L to 99L, TorrentStreamServer.byteRange("bytes=10-", 100))
        assertNull(TorrentStreamServer.byteRange("bytes=100-", 100))
        assertNull(TorrentStreamServer.byteRange("bytes=8-2", 100))
        assertNull(TorrentStreamServer.byteRange("bytes=1-2,5-6", 100))
    }

    @Test fun streamingBlocksUnverifiedBytesAndServesOnlyTheRequestedVerifiedRange() {
        val data = ByteArray(4096) { (it % 251).toByte() }
        val file = File.createTempFile("torrent-stream", ".mp4").apply { writeBytes(data) }
        val ready = AtomicBoolean(false)
        val prioritized = CompletableFuture<Pair<Long, Long>>()
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        try {
            TorrentStreamServer(file, data.size.toLong(), 512, 1024,
                verified = { it == 1 && ready.get() },
                prioritize = { first, last -> prioritized.complete(first to last) }).use { stream ->
                val download = CompletableFuture.supplyAsync {
                    client.newCall(Request.Builder().url(stream.uri).header("Range", "bytes=600-999").build()).execute().use {
                        assertEquals(206, it.code)
                        assertEquals("bytes 600-999/4096", it.header("Content-Range"))
                        it.body!!.bytes()
                    }
                }
                assertEquals(600L to 999L, prioritized.get(3, TimeUnit.SECONDS))
                assertFalse("Preallocated bytes must not be delivered before verification", download.isDone)
                ready.set(true)
                assertArrayEquals(data.copyOfRange(600, 1000), download.get(3, TimeUnit.SECONDS))
                client.newCall(Request.Builder().url(stream.uri).header("Range", "bytes=9999-").build()).execute().use { assertEquals(416, it.code) }
                client.newCall(Request.Builder().url(stream.uri + "wrong").build()).execute().use { assertEquals(404, it.code) }
            }
        } finally { file.delete() }
    }

    @Test fun closingStreamCancelsReadersWaitingForMissingPieces() {
        val file = File.createTempFile("torrent-stream", ".mkv").apply { writeBytes(ByteArray(1024)) }
        val requested = CompletableFuture<Unit>()
        val stream = TorrentStreamServer(file, 1024, 0, 1024, verified = { false }, prioritize = { _, _ -> requested.complete(Unit) })
        try {
            val reader = CompletableFuture.supplyAsync {
                runCatching { OkHttpClient().newCall(Request.Builder().url(stream.uri).build()).execute().use { it.body!!.bytes() } }.isFailure
            }
            requested.get(3, TimeUnit.SECONDS)
            stream.close()
            assertTrue(reader.get(3, TimeUnit.SECONDS))
        } finally { stream.close(); file.delete() }
    }
}
