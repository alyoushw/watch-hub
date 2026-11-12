package com.alyoushw.watchhub

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.data.AnimeEpisodeRef
import com.alyoushw.watchhub.data.Net
import okhttp3.Request
import kotlinx.coroutines.runBlocking
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.PlayerQueue
import com.alyoushw.watchhub.player.QueueItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackSmokeTest {
    @Test fun animeifySourceResolvesAndPlaysInExistingPlayer() = runBlocking {
        // Forks/local builds have no owner token; publication independently requires it.
        assumeTrue("Owner source token is required for the live source smoke test", BuildConfig.ANIMEIFY_TOKEN.isNotBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as WatchHubApp
        val source = app.container.animeify
        val title = checkNotNull(source.search("Naruto", false).firstOrNull { it.malId == 20 })
        val episode = source.episodes(title.id).first()
        val ref = AnimeEpisodeRef(title.id, 20, title.type, title.season, episode.number, "FRLink")
        assertTrue(source.servers(ref).any { it.key == "FRLink" })
        val url = source.resolve(ref)
        // Differentiate resolver/CDN failures from a player regression without logging signed URLs.
        Net.streams.newCall(Request.Builder().url(url).header("Range", "bytes=0-1023").build()).execute().use { response ->
            assertTrue("Resolved MP4 range request returned HTTP ${response.code}", response.isSuccessful)
            val bytes = checkNotNull(response.body).byteStream().readBytesAtMost(1024)
            assertTrue("Resolved source must return MP4 data", bytes.size > 8 && String(bytes, 4, 4) == "ftyp")
        }
        val key = "animeify:${title.id}:${episode.number}"
        app.container.history.remove(key)
        PlayerQueue.items = listOf(QueueItem(emptyList(), WatchEntry(
            key, WatchEntry.ANIME, episode.number, "Animeify playback smoke", seriesId = title.id,
            accountId = "animeify", animeRef = ref,
        )))
        PlayerQueue.index = 0
        try {
            ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java)).use {
                val deadline = SystemClock.elapsedRealtime() + 60_000
                while (SystemClock.elapsedRealtime() < deadline && (app.container.history.get(key)?.positionMs ?: 0) < 500) {
                    SystemClock.sleep(250)
                }
                val saved = checkNotNull(app.container.history.get(key))
                assertTrue("Animeify MP4 must actually play in the existing player", saved.positionMs >= 500)
                assertTrue(saved.durationMs > 60_000)
                assertEquals(ref, saved.animeRef)
            }
        } finally {
            app.container.history.remove(key)
            PlayerQueue.items = emptyList()
        }
    }

    private fun java.io.InputStream.readBytesAtMost(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val n = read(buffer, count, limit - count)
            if (n < 0) break
            count += n
        }
        return buffer.copyOf(count)
    }

    @Test fun existingHomeLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                assertTrue(activity.window.decorView.isShown)
                assertNotNull((activity.application as WatchHubApp).container.updater)
            }
        }
    }

    @Test fun existingPlayerDecodesMp4AndPersistsProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as WatchHubApp
        val file = File(context.cacheDir, "player-smoke.mp4")
        instrumentation.context.assets.open("player-smoke.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val key = "test:movie:player-smoke"
        app.container.history.remove(key)
        PlayerQueue.items = listOf(QueueItem(listOf(file.toURI().toString()),
            WatchEntry(key, WatchEntry.MOVIE, "player-smoke", "اختبار المشغل الحالي")))
        PlayerQueue.index = 0
        try {
            ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java)).use {
                val deadline = SystemClock.elapsedRealtime() + 25_000
                while (SystemClock.elapsedRealtime() < deadline && (app.container.history.get(key)?.positionMs ?: 0) < 500) {
                    SystemClock.sleep(250)
                }
                val saved = checkNotNull(app.container.history.get(key))
                assertTrue("The existing player must play and save progress", saved.positionMs >= 500)
                assertTrue("The MP4 duration must be detected", saved.durationMs >= 10_000)
            }
        } finally {
            app.container.history.remove(key)
            file.delete()
            PlayerQueue.items = emptyList()
        }
    }
}
