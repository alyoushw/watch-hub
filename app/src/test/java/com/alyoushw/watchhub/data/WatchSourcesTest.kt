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

    @Test fun freshResolutionAndHistoryNeverPersistTransientPlaybackUrls() = runBlocking {
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

    @Test fun manifestsAndProbesRejectMalformedServerResponses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>offline</html>"))
            assertThrows(SourceException::class.java) { runBlocking { ManifestSourceProvider(server.url("/catalogue").toString()).search(movie) } }
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not a video</html>"))
            assertFalse(StreamProbe().compatible(SourceVariant("hd", "HD", server.url("/not-video").toString(), SourceTransport.MP4)))
        }
    }

    @Test fun magnetAndTorrentAreHandledByPreparationEngineOnly() = runBlocking {
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

    @Test fun indexCannotIntroduceAnUnauthorizedTorrentHash() = runBlocking {
        val allowed = "a".repeat(40)
        val foreign = "b".repeat(40)
        val authorized = source().copy(variants = listOf(SourceVariant("torrent", "Torrent", "magnet:?xt=urn:btih:$allowed", SourceTransport.MAGNET)))
        val catalogue = object : WatchSourceProvider { override suspend fun search(query: SourceQuery) = listOf(authorized) }
        val index = object : TorrentIndex { override suspend fun search(query: SourceQuery) = listOf(TorrentIndexRecord(foreign, "Sintel 2010", "tt1727587", 1000)) }
        val results = IndexedCatalogueProvider(catalogue, index).search(movie)
        assertEquals(listOf(authorized), results)
        assertFalse(results.toString().contains(foreign))
        val parsed = TpbTorrentIndex.parse("""[{"info_hash":"$allowed","name":"Sintel.2010.1080p","imdb":"1727587","seeders":"5"},{"info_hash":"$foreign","name":"Sintel.2011.1080p","imdb":"1727587","seeders":"9"}]""", movie)
        assertEquals(listOf(allowed), parsed.map { it.hash })
    }
}
