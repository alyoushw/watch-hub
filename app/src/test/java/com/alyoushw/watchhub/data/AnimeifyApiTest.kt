package com.alyoushw.watchhub.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AnimeifyApiTest {
    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/animeify/$name.json")).readText()

    @Test fun `catalogue uses original MAL id instead of source database id`() {
        val list = AnimeifyApi.titles(Json.parseToJsonElement(fixture("titles")))
        val naruto = list.first { it.id == "xNaruto" }
        assertEquals(20, naruto.malId)
        assertEquals(1, naruto.season)
        assertEquals("SERIES", naruto.type)
    }

    @Test fun `episodes are numeric sorted deduplicated and retain specials`() {
        val result = AnimeifyApi.parseEpisodes(Json.parseToJsonElement("""[
            {"Episode":"10"}, {"Episode":"2"}, {"Episode":"1.5","ExtraEpisodes":"خاصة"},
            {"Episode":"2"}, {"Episode":"NaN"}, {"Episode":"oops"}, {"Episode":"-1"}
        ]"""))
        assertEquals(listOf("1.5", "2", "10"), result.map { it.number })
        assertEquals("خاصة", result.first().extra)
        assertEquals(3, AnimeifyApi.parseEpisodes(Json.parseToJsonElement(fixture("episodes"))).size)
        assertEquals("0", AnimeifyApi.parseEpisodes(Json.parseToJsonElement("""[{"Episode":"0"}]""")).single().number)
    }

    @Test fun `server qualities exclude empty fields and unavailable sentinel values`() {
        val result = AnimeifyApi.parseServers(Json.parseToJsonElement("""{
            "FRLink":"hd_id", "FRLowQ":"sd_id", "FRFhdQ":"fullhd_id",
            "SVLink":"1", "SVLowQ":"", "OKLink":null
        }""").jsonObject)
        assertEquals(listOf("FRFhdQ", "FRLink", "FRLowQ"), result.map { it.key })
        assertTrue(result.all { "MP4" in it.label })
    }

    @Test fun `mediafire parser decodes HTML entities and ignores unrelated advertising links`() {
        val url = AnimeifyApi.mediaFireUrl("""<a href="https://ads.example/file.mp4">ad</a>
            <a href="https://download1.mediafire.com/path/video.mp4?x=1&amp;y=2" id="downloadButton">Download</a>""")
        assertEquals("https://download1.mediafire.com/path/video.mp4?x=1&y=2", url)
        assertNull(AnimeifyApi.mediaFireUrl("""<a id="downloadButton" href="https://mediafire.com.attacker.test/video.mp4">bad</a>"""))
    }

    @Test fun `mediafire scrambled links support Base64 and malformed pages fail cleanly`() {
        val url = "https://download2.mediafire.com/path/video.mp4"
        val encoded = Base64.getEncoder().encodeToString(url.toByteArray())
        assertEquals(url, AnimeifyApi.mediaFireUrl("""<a data-scrambled-url="$encoded">Download</a>"""))
        assertNull(AnimeifyApi.mediaFireUrl("<html>file removed</html>"))
        assertNull(AnimeifyApi.mediaFireUrl("""<a data-scrambled-url="invalid!">Download</a>"""))
    }

    @Test fun `request encodes search text and refreshes source URL on each resolution`() = runBlocking {
        MockWebServer().use { server ->
            val api = AnimeifyApi("test-token", server.url("/").toString(), OkHttpClient())
            server.enqueue(MockResponse().setBody(fixture("titles")))
            assertFalse(api.search("Naruto ناروتو", false).isEmpty())
            val search = server.takeRequest()
            assertEquals("POST", search.method)
            assertEquals("/anime/load_anime_list_v2.php", search.path)
            assertTrue(search.body.readUtf8().contains("Token=test-token"))
            val ref = AnimeEpisodeRef("xNaruto", 20, "SERIES", 1, "1", "FRLink")
            for (number in 1..2) {
                server.enqueue(MockResponse().setBody("""{"CurrentEpisode":{"FRLink":"https://cdn.example/$number.mp4"}}"""))
                assertEquals("https://cdn.example/$number.mp4", api.resolve(ref))
                val request = server.takeRequest()
                assertEquals("/anime/load_servers.php", request.path)
                assertTrue(request.body.readUtf8().contains("AnimeId=xNaruto"))
            }
        }
    }

    @Test fun `missing chosen server does not silently play another quality`() = runBlocking {
        MockWebServer().use { server ->
            val api = AnimeifyApi("test-token", server.url("/").toString(), OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"CurrentEpisode":{"FRLink":"hd_id"}}"""))
            try {
                api.resolve(AnimeEpisodeRef("xNaruto", 20, "SERIES", 1, "1", "FRLowQ"))
                fail("A missing SD source must require selecting another server")
            } catch (expected: AnimeifyException) { assertTrue(expected.message.orEmpty().contains("السيرفر")) }
        }
    }

    @Test fun `source errors are reported instead of parsed as empty catalogue`() = runBlocking {
        MockWebServer().use { server ->
            val api = AnimeifyApi("test-token", server.url("/").toString(), OkHttpClient())
            for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setBody("<html>error</html>"))) {
                server.enqueue(response)
                try { api.search("Naruto", false); fail("Expected source error") }
                catch (_: AnimeifyException) { }
            }
        }
    }

    @Test fun `history remains backward compatible and stores stable references only`() {
        val old = Json.decodeFromString(WatchEntry.serializer(), """{"key":"x:episode:1","kind":"episode","itemId":"1","title":"Old"}""")
        assertNull(old.animeRef)
        val ref = AnimeEpisodeRef("xNaruto", 20, "SERIES", 1, "1", "FRLink")
        val entry = WatchEntry("animeify:xNaruto:1", WatchEntry.ANIME, "1", "Naruto", animeRef = ref)
        val encoded = Json.encodeToString(WatchEntry.serializer(), entry)
        assertFalse(encoded.contains("https://"))
        assertEquals(entry, Json.decodeFromString(WatchEntry.serializer(), encoded))
        assertTrue(old.isEpisode)
        assertTrue(entry.isEpisode)
    }
}
