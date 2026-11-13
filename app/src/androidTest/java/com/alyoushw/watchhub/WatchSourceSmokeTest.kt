package com.alyoushw.watchhub

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.alyoushw.watchhub.data.*
import com.alyoushw.watchhub.player.CaptionOverlay
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.PlayerQueue
import com.alyoushw.watchhub.player.QueueItem
import com.alyoushw.watchhub.ui.WatchHubTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class WatchSourceSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as WatchHubApp).container

    @Test fun authorizedManifestPlaysMp4HlsAndMkvWithSubtitlesInTheExistingPlayer() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val query = SourceQuery(MediaType.MOVIE, 99999999, "Watch Hub owned test fixture", 2025)
            val subtitles = listOf(SourceSubtitle("english", "Fixture English", "en", server.url("/captions.srt").toString(), "srt"))
            val source = WatchSource("fixture", "test-owner", "Owned test fixture", query,
                listOf(SourceVariant("mp4", "MP4", server.url("/sample.mp4").toString(), SourceTransport.MP4),
                    SourceVariant("hls", "HLS", server.url("/hls/playlist.m3u8").toString(), SourceTransport.HLS),
                    SourceVariant("mkv", "MKV", server.url("/sample.mkv").toString(), SourceTransport.MKV)),
                subtitles, "Generated owned fixture", "https://example.org/fixture-licence")
            val catalogue = Json.encodeToString(ListSerializer(WatchSource.serializer()), listOf(source))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/catalogue" -> MockResponse().setBody(catalogue).addHeader("Content-Type", "application/json")
                    "/captions.srt" -> MockResponse().setBody("1\n00:00:00,500 --> 00:00:10,000\nHello world.\n")
                    "/sample.mp4" -> response(asset("player-smoke.mp4"), request, "video/mp4")
                    "/sample.mkv" -> response(asset("source-smoke.mkv"), request, "video/x-matroska")
                    else -> {
                        val file = request.requestUrl?.encodedPath?.removePrefix("/hls/").orEmpty()
                        if (file in setOf("playlist.m3u8", "init.mp4", "segment-0.m4s", "segment-1.m4s"))
                            response(asset("source-hls/$file"), request, if (file.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "video/mp4")
                        else MockResponse().setResponseCode(404)
                    }
                }
            }
            val previous = container.settings.sourceCatalogue.value
            container.settings.setSourceCatalogue(server.url("/catalogue").toString())
            try {
                val found = container.watchSources.search(query).sources.single()
                assertEquals(3, found.variants.size)
                assertTrue(container.watchSources.search(query.copy(year = 2024)).sources.isEmpty())
                for (variant in found.variants) {
                    val key = "test:source:${variant.id}"
                    val ref = WatchSourceRef(found.providerId, found.id, query, variant.id)
                    val entry = WatchEntry(key, WatchEntry.MOVIE, "fixture", "Owned ${variant.label} fixture", sourceRef = ref)
                    container.history.remove(key)
                    PlayerQueue.items = listOf(QueueItem(emptyList(), entry))
                    PlayerQueue.index = 0
                    try {
                        ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java)).use {
                            if (variant.id == "mp4") {
                                compose.onNodeWithContentDescription("الترجمة").performClick()
                                compose.waitUntil(15_000) {
                                    compose.onAllNodes(hasText("Fixture English", substring = true)).fetchSemanticsNodes().isNotEmpty()
                                }
                            }
                            val deadline = SystemClock.elapsedRealtime() + 30_000
                            while (SystemClock.elapsedRealtime() < deadline && (container.history.get(key)?.positionMs ?: 0) < 500) SystemClock.sleep(100)
                            val played = checkNotNull(container.history.get(key))
                            assertTrue("${variant.label} must decode in the existing player", played.positionMs >= 500)
                            assertTrue(played.durationMs in 10_000..15_000)
                            assertEquals(ref, played.sourceRef)
                            if (variant.id == "mp4") {
                                val device = UiDevice.getInstance(instrumentation)
                                device.pressBack()
                                device.pressDPadUp()
                                compose.onNodeWithContentDescription("الجودة").performClick()
                                compose.onNodeWithTag("quality-options").performScrollToNode(hasText("HLS"))
                                compose.onNodeWithText("HLS").performClick()
                                val switched = SystemClock.elapsedRealtime() + 15_000
                                while (SystemClock.elapsedRealtime() < switched && container.history.get(key)?.sourceRef?.variantId != "hls") SystemClock.sleep(100)
                                assertEquals("hls", container.history.get(key)?.sourceRef?.variantId)
                                assertTrue("Changing source quality must retain position", (container.history.get(key)?.positionMs ?: 0) >= played.positionMs - 500)
                            }
                        }
                    } finally { container.history.remove(key) }
                }
            } finally {
                container.settings.setSourceCatalogue(previous)
                PlayerQueue.items = emptyList()
            }
        }
    }

    @Test fun nativeTorrentAndMagnetPreparePieceVerifiedVideoInsteadOfPlayingTheMagnet() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val bytes = asset("player-smoke.mp4")
            val seed = File(context.cacheDir, "owned-torrent-fixture.mp4").apply { writeBytes(bytes) }
            val torrent = TorrentBuilder().path(seed).pieceSize(16 * 1024).addUrlSeed(server.url("/seed/").toString()).generate().entry().bencode()
            val hash = TorrentInfo(torrent).infoHash().toHex()
            val requests = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath == "/fixture.torrent") return MockResponse().setBody(Buffer().write(torrent))
                    requests.incrementAndGet()
                    return response(bytes, request, "video/mp4")
                }
            }
            val query = SourceQuery(MediaType.MOVIE, 99999999, "Owned test fixture", 2025)
            val engine = TorrentPlaybackEngine(context)
            val metadataUrl = server.url("/fixture.torrent").toString()
            val magnet = "magnet:?xt=urn:btih:$hash&xs=" + URLEncoder.encode(metadataUrl, "UTF-8")
            try {
                listOf(SourceVariant("torrent", "Torrent", metadataUrl, SourceTransport.TORRENT),
                    SourceVariant("magnet", "Magnet", magnet, SourceTransport.MAGNET)).forEach { variant ->
                    File(context.filesDir, "source-torrents/$hash").deleteRecursively()
                    val before = requests.get()
                    val uri = withTimeout(90_000) { engine.prepare(variant, query) {} }
                    assertTrue(uri.startsWith("file:"))
                    assertFalse(uri.startsWith("magnet:"))
                    val file = File(java.net.URI(uri))
                    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
                    assertTrue("The torrent engine must download and verify real fixture bytes", requests.get() > before)
                }
            } finally {
                seed.delete()
                File(context.filesDir, "source-torrents/$hash").deleteRecursively()
            }
        }
    }

    @Test fun realOnDeviceArabicCaptionsAppearAtDelayedVideoTimeAndClearAfterSeek() = runBlocking<Unit> {
        val phrase = "Hello world."
        val translator = CaptionTranslator()
        val arabic = try { withTimeout(180_000) { translator.translate(phrase, "en") } } finally { translator.close() }
        assertTrue("The actual downloaded model must return Arabic text", Regex("[\\u0600-\\u06ff]").containsMatchIn(arabic))
        val video = File(context.cacheDir, "caption-smoke.mp4").apply { writeBytes(asset("player-smoke.mp4")) }
        val captions = File(context.cacheDir, "caption-smoke.vtt").apply { writeText(SubtitleFiles.webVtt(listOf(TimedCaption(1000, 10_000, phrase)))) }
        val device = UiDevice.getInstance(instrumentation)
        var player: ExoPlayer? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val media = ExoPlayer.Builder(activity).build()
                    player = media
                    media.trackSelectionParameters = media.trackSelectionParameters.buildUpon().setPreferredTextLanguage("en").build()
                    media.setMediaItem(MediaItem.Builder().setUri(video.toURI().toString()).setSubtitleConfigurations(listOf(
                        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(captions)).setMimeType(MimeTypes.TEXT_VTT)
                            .setLanguage("en").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())).build())
                    activity.setContent {
                        WatchHubTheme(forceDark = true) {
                            Box(Modifier.fillMaxSize()) {
                                AndroidView(factory = { PlayerView(it).apply { this.player = media; useController = false; subtitleView?.visibility = View.GONE } }, modifier = Modifier.fillMaxSize())
                                CaptionOverlay(media, true, 1000, "en", true, 0.065f, android.graphics.Color.YELLOW, true, Modifier.fillMaxSize()) {}
                            }
                        }
                    }
                    media.prepare()
                    media.playWhenReady = true
                }
                assertTrue("Translated Arabic must reach the actual subtitle view", device.wait(Until.hasObject(By.descContains(arabic)), 20_000) == true)
                scenario.onActivity { assertTrue(checkNotNull(player).currentPosition >= 1800) }
                device.executeShellCommand("mkdir -p /sdcard/Download/watchhub-smoke")
                device.executeShellCommand("screencap -p /sdcard/Download/watchhub-smoke/arabic-translated-caption.png")
                scenario.onActivity { checkNotNull(player).pause(); checkNotNull(player).seekTo(0) }
                assertTrue("Seeking must clear delayed captions from the previous position", device.wait(Until.gone(By.descContains(arabic)), 5000) == true)
                scenario.onActivity { checkNotNull(player).release(); player = null }
            }
        } finally {
            if (player != null) instrumentation.runOnMainSync { player?.release() }
            video.delete()
            captions.delete()
        }
    }

    private fun asset(path: String): ByteArray = instrumentation.context.assets.open(path).use { it.readBytes() }

    private fun response(bytes: ByteArray, request: RecordedRequest, type: String): MockResponse {
        val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        if (range == null) return MockResponse().setBody(Buffer().write(bytes)).addHeader("Content-Type", type)
        val start = range.groupValues[1].toInt()
        val end = (range.groupValues[2].toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
        if (start !in bytes.indices || end < start) return MockResponse().setResponseCode(416)
        return MockResponse().setResponseCode(206).addHeader("Content-Type", type).addHeader("Accept-Ranges", "bytes")
            .addHeader("Content-Range", "bytes $start-$end/${bytes.size}").setBody(Buffer().write(bytes, start, end - start + 1))
    }
}
