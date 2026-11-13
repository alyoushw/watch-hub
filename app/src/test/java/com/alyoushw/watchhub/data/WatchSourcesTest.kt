package com.alyoushw.watchhub.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WatchSourcesTest {
    private val movie = SourceQuery(MediaType.MOVIE, 45745, "Sintel", 2010, imdbId = "tt1727587")
    private val episode = SourceQuery(MediaType.TV, 100, "Fixture Series", 2020, 2, 3, "tt1234567")
    private fun source(query: SourceQuery = movie, url: String = "https://example.org/video.mp4") = WatchSource(
        "fixture", "owner", "Owner", query, listOf(SourceVariant("hd", "HD", url, SourceTransport.MP4)),
        attribution = "Owner-authorized fixture", licenceUrl = "https://example.org/rights")

    @Test fun matchesAllIdentityFieldsAndEpisodeCoordinates() {
        assertTrue(movie.matches(movie.copy(originalTitle = "SINTEL")))
        assertFalse(movie.matches(movie.copy(tmdbId = 1)))
        assertFalse(movie.matches(movie.copy(originalTitle = "Another Film")))
        assertFalse(movie.matches(movie.copy(year = 2011)))
        assertFalse(movie.matches(movie.copy(imdbId = "tt0000001")))
        assertFalse(episode.matches(episode.copy(season = 1)))
        assertFalse(episode.matches(episode.copy(episode = 4)))
        assertFalse(episode.copy(episode = null).valid)
        assertFalse(movie.copy(year = 0).valid)
    }

    @Test fun rejectsInvalidSchemesMissingAttributionAndTemporaryIdentity() {
        assertTrue(source().compatible(movie))
        assertFalse(source(url = "javascript:alert(1)").compatible(movie))
        assertFalse(source().copy(attribution = "").compatible(movie))
        assertFalse(source().copy(id = "https://example.org/temporary?token=abc").compatible(movie))
        assertFalse(source().copy(query = movie.copy(year = 2012)).compatible(movie))
    }

    @Test fun validatesMp4HlsMkvAndRejectsHtmlAndUnsupportedDrm() {
        assertTrue(StreamProbe.signatureMatches(SourceTransport.MP4, byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray()))
        assertTrue(StreamProbe.signatureMatches(SourceTransport.MKV, byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))
        assertTrue(StreamProbe.signatureMatches(SourceTransport.HLS, "#EXTM3U\n#EXTINF:6.0,\nsegment.ts".toByteArray()))
        assertFalse(StreamProbe.signatureMatches(SourceTransport.MP4, "<html>expired link</html>".toByteArray()))
        assertFalse(StreamProbe.signatureMatches(SourceTransport.HLS, "#EXTM3U".toByteArray()))
        assertFalse(StreamProbe.signatureMatches(SourceTransport.HLS, "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES\n#EXTINF:4,\na.ts".toByteArray()))
    }

    @Test fun freshResolutionAndHistoryNeverPersistTransientPlaybackUrls() = runBlocking<Unit> {
        var calls = 0
        val provider = object : WatchSourceProvider {
            override suspend fun search(query: SourceQuery): List<WatchSource> = listOf(source(url = "https://example.org/video.mp4?lease=${++calls}"))
        }
        val probe = SourceProbe { true }
        val repository = WatchSources({ listOf(provider) }, probe)
        val engines = PlaybackEngines(listOf(DirectPlaybackEngine(probe)))
        val ref = WatchSourceRef("owner", "fixture", movie, "hd")
        val first = repository.resolve(ref, engines)
        val second = repository.resolve(ref, engines)
        assertNotEquals(first.uri, second.uri)
        assertEquals(2, calls)
        val history = WatchEntry("source:movie:45745", WatchEntry.MOVIE, "45745", "Sintel", sourceRef = ref)
        val json = Json.encodeToString(WatchEntry.serializer(), history)
        assertFalse(json.contains("lease="))
        assertFalse(json.contains("example.org"))
        assertEquals(ref, Json.decodeFromString(WatchEntry.serializer(), json).sourceRef)
        assertNull(Json.decodeFromString(WatchEntry.serializer(), """{"key":"old","kind":"movie","itemId":"1","title":"Old"}""").sourceRef)
    }

    @Test fun manifestsAndProbesRejectMalformedServerResponses() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>offline</html>"))
            assertThrows(SourceException::class.java) { runBlocking { ManifestSourceProvider(server.url("/catalogue").toString()).search(movie) } }
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not a video</html>"))
            assertFalse(StreamProbe().compatible(SourceVariant("hd", "HD", server.url("/not-video").toString(), SourceTransport.MP4)))
        }
    }

    @Test fun slowFragmentedHlsPlaylistIsStillRecognized() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nsegment.ts\n")
                .throttleBody(8, 10, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertTrue(StreamProbe().compatible(SourceVariant("hls", "HLS", server.url("/playlist").toString(), SourceTransport.HLS)))
        }
    }

    @Test fun magnetAndTorrentAreHandledByPreparationEngineOnly() = runBlocking<Unit> {
        val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
        assertEquals("a".repeat(40), magnetHash(magnet))
        assertEquals("ff".repeat(20), magnetHash("magnet:?xt=urn:btih:" + "7".repeat(32)))
        assertNull(magnetHash("magnet:?xt=urn:btih:bad"))
        assertNull(magnetHash("https://example.org/video.mp4"))
        val variant = SourceVariant("torrent", "Torrent", magnet, SourceTransport.MAGNET)
        assertThrows(SourceException::class.java) { runBlocking { DirectPlaybackEngine(SourceProbe { true }).prepare(variant, movie) {} } }
    }

    @Test fun torrentFileSelectionNeverSubstitutesAnotherEpisodeOrSample() {
        val paths = listOf("Fixture.Series.S02E02.mkv", "sample/Fixture.Series.S02E03.mp4", "Fixture.Series.S02E03.mkv", "Fixture.Series.S02E030.mkv")
        assertEquals(2, TorrentPlaybackEngine.chooseVideoFile(paths, listOf(9999, 10, 500, 99999), episode))
        assertNull(TorrentPlaybackEngine.chooseVideoFile(paths.take(2), listOf(9999, 10), episode))
        assertEquals(1, TorrentPlaybackEngine.chooseVideoFile(listOf("Another.2010.mkv", "Sintel.2010.mp4"), listOf(9999, 100), movie))
    }

    @Test fun directIndexSearchNeedsNoCatalogueAndResolvesTheSelectedResultAgain() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val hash = "a".repeat(40)
            val payload = """[{"id":"123","info_hash":"$hash","name":"Sintel.2010.1080p","imdb":"tt1727587","seeders":"5","size":"400000000","category":"207"}]"""
            server.enqueue(MockResponse().setBody(payload))
            server.enqueue(MockResponse().setBody(payload.replace("\"5\"", "\"8\"")))
            val provider = TpbSourceProvider(TpbTorrentIndex(server.url("/").toString()))
            val repository = WatchSources({ listOf(provider) })
            val result = repository.search(movie).sources.single()
            assertEquals("tpb", result.providerId)
            assertTrue(result.attribution.contains("5"))
            assertEquals(hash, magnetHash(result.variants.single().url))
            val request = server.takeRequest()
            assertEquals("/q.php", request.requestUrl?.encodedPath)
            assertEquals("Sintel 2010", request.requestUrl?.queryParameter("q"))
            assertEquals("200", request.requestUrl?.queryParameter("cat"))
            var prepared = false
            val engines = PlaybackEngines(listOf(object : PlaybackEngine {
                override fun supports(transport: SourceTransport) = transport == SourceTransport.MAGNET
                override suspend fun prepare(variant: SourceVariant, query: SourceQuery, progress: (Float) -> Unit): String {
                    assertEquals(hash, magnetHash(variant.url)); prepared = true; return "file:///owned-fixture.mp4"
                }
            }))
            val resolved = repository.resolve(WatchSourceRef("tpb", result.id, movie, "magnet"), engines)
            assertTrue(prepared)
            assertEquals("file:///owned-fixture.mp4", resolved.uri)
            assertTrue(resolved.source.attribution.contains("8"))
            assertEquals("123", server.takeRequest().requestUrl?.queryParameter("id"))
        }
    }

    @Test fun indexRejectsWrongYearsImdbTypesEpisodesAndDeadOrDuplicateEntries() {
        val hash = "a".repeat(40)
        fun row(name: String, imdb: String = "tt1727587", peers: Int = 5, category: Int = 207) =
            """{"id":"123","info_hash":"$hash","name":"$name","imdb":"$imdb","seeders":"$peers","category":"$category"}"""
        assertTrue(TpbTorrentIndex.parse("[" + row("Sintel.2011.1080p") + "]", movie).isEmpty())
        assertTrue(TpbTorrentIndex.parse("[" + row("Sintel.2010", "tt9999999") + "]", movie).isEmpty())
        assertTrue(TpbTorrentIndex.parse("[" + row("Sintel.2010", peers = 0) + "]", movie).isEmpty())
        assertTrue(TpbTorrentIndex.parse("[" + row("Sintel.2010", category = 101) + "]", movie).isEmpty())
        assertTrue(TpbTorrentIndex.parse("[" + row("Sintel.2010.S01E01") + "]", movie).isEmpty())
        val good = row("Sintel.2010.1080p")
        assertEquals(1, TpbTorrentIndex.parse("[$good,$good]", movie).size)
        assertEquals(1, TpbTorrentIndex.parse("[" + row("Sintel.2010.720p", imdb = "") + "]", movie.copy(imdbId = null)).size)
        fun tv(name: String, imdb: String = "tt1234567") = TpbTorrentIndex.parse("[" + row(name, imdb, category = 208) + "]", episode)
        assertEquals(1, tv("Fixture.Series.S02E03.1080p").size)
        assertTrue(tv("Fixture.Series.S02E04.1080p").isEmpty())
        assertTrue(tv("Fixture.Series.S02E03E04.1080p").isEmpty())
        assertTrue(tv("Fixture.Series.S02E03-S02E04.1080p").isEmpty())
        assertTrue(tv("Fixture.Series.S02E03.1080p", imdb = "").isEmpty())
        assertEquals(1, tv("Fixture.Series.2020.2x03.1080p", imdb = "").size)
        assertEquals(1, TpbTorrentIndex.parse("[" + row("1917.2019.1080p", imdb = "") + "]",
            SourceQuery(MediaType.MOVIE, 530915, "1917", 2019)).size)
    }

    @Test fun indexLookupCannotSilentlyChangeTheSelectedTorrentHash() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"123","info_hash":"${"b".repeat(40)}","name":"Sintel.2010.1080p","imdb":"tt1727587","seeders":"5"}]"""))
            val provider = TpbSourceProvider(TpbTorrentIndex(server.url("/").toString()))
            assertNull(provider.find(movie, "123:${"a".repeat(40)}"))
        }
    }
}
