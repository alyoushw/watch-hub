package com.alyoushw.watchhub

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.alyoushw.watchhub.data.*
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.PlayerQueue
import com.alyoushw.watchhub.player.QueueItem
import com.alyoushw.watchhub.ui.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class AnimeFlowSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as WatchHubApp).container

    @Test fun watchActionOpensDedicatedEpisodePageAndSeasonDownloadPicker() = runBlocking {
        assumeTrue(BuildConfig.ANIMEIFY_TOKEN.isNotBlank())
        val title = container.animeify.search("Honey Lemon Soda", false).first { it.title.equals("Honey Lemon Soda", true) }
        val lookup = AnimeLookup(title.title, title.title, false, title.poster, MediaType.ANIME,
            title.malId ?: 20, title.malId,
            overview = "بعد المدرسة الإعدادية، مع التنمر والألقاب، تشعر أوكا إيشيموري بالحماس لبدء بداية جديدة في المدرسة الثانوية. يدعم كاي أوكا في جهودها للتأقلم مع زملائها الجدد في الفصل.")
        val device = UiDevice.getInstance(instrumentation)
        val opened = AtomicBoolean(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                WatchHubTheme(forceDark = true) {
                    val nav = rememberNavController()
                    var showAction by remember { mutableStateOf(true) }
                    Box {
                        WatchHubRoot(nav)
                        if (showAction) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(lookup.title)
                                AnimeifyWatchButton(lookup, onOpen = {
                                    nav.navigate(it.route())
                                    opened.set(true)
                                    showAction = false
                                })
                            }
                        }
                    }
                }
            } }
            try {
                waitForText("تشغيل أنمي فاي")
                compose.onNodeWithText("تشغيل أنمي فاي").performClick()
                instrumentation.waitForIdleSync()
                assertTrue("The watch button must invoke its navigation callback", opened.get())
                waitForText("تنزيل الحلقات للمشاهدة بدون نت", substring = true)
                waitForText("S01 E01", substring = true, timeout = 30_000)
                compose.onNodeWithText("📥 تنزيل الحلقات للمشاهدة بدون نت").assertExists()
                device.waitForIdle(2_000)
                saveScreenshot("animeify-episodes")
                compose.onNodeWithText("📥 تنزيل الحلقات للمشاهدة بدون نت").performClick()
                waitForText("تنزيل الموسم", substring = true)
                waitForText("MediaFire", substring = true, timeout = 30_000)
                saveScreenshot("animeify-download-picker")
            } catch (failure: Throwable) {
                saveScreenshot("animeify-flow-failure")
                val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                device.dumpWindowHierarchy(File(directory, "animeify-flow-failure.xml"))
                device.executeShellCommand("cp ${directory.absolutePath}/animeify-flow-failure.xml /sdcard/Download/watchhub-smoke/")
                throw failure
            }
        }
    }

    @Test fun animeDownloadsResolveReferencesAndCanBePaused() {
        assumeTrue(BuildConfig.ANIMEIFY_TOKEN.isNotBlank())
        val entry = WatchEntry("test:anime:download", WatchEntry.ANIME, "1", "Download smoke",
            animeRef = AnimeEpisodeRef("xNaruto", 20, "SERIES", 1, "1", "FRLink"))
        ActivityScenario.launch(MainActivity::class.java).use {
            container.downloads.remove(entry.key)
            try {
                container.downloads.startAnime(listOf(entry))
                val download = checkNotNull(container.downloads.find(entry.key))
                assertEquals("", download.url)
                assertEquals(entry.animeRef, container.downloads.entryOf(download)?.animeRef)
                assertTrue(container.downloads.canPause(download))
                val deadline = SystemClock.elapsedRealtime() + 60_000
                while (SystemClock.elapsedRealtime() < deadline && File(download.file).length() < 4096 && !container.downloads.state(download).failed) {
                    SystemClock.sleep(200)
                }
                assertTrue("The existing download worker must download Animeify MP4 bytes", File(download.file).length() >= 4096)
                container.downloads.pause(entry.key)
                assertTrue(container.downloads.state(download).paused || container.downloads.state(download).done)
            } finally { container.downloads.remove(entry.key) }
        }
    }

    @Test fun downloadedAnimeUsesLocalFileInsteadOfResolvingRemoteServer() {
        val entry = WatchEntry("test:anime:offline", WatchEntry.ANIME, "1", "Offline anime smoke",
            animeRef = AnimeEpisodeRef("offline-fixture", 20, "SERIES", 1, "1", "FRLink"))
        MockWebServer().use { server ->
            val bytes = instrumentation.context.assets.open("player-smoke.mp4").use { it.readBytes() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)).addHeader("Content-Type", "video/mp4"))
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    container.downloads.remove(entry.key)
                    // Seed a completed file through the real downloader using a local fixture CDN.
                    container.downloads.start(entry.copy(animeRef = null), server.url("/fixture.mp4").toString())
                    val deadline = SystemClock.elapsedRealtime() + 30_000
                    while (SystemClock.elapsedRealtime() < deadline && container.downloads.localUrl(entry.key) == null) SystemClock.sleep(200)
                    assertNotNull(container.downloads.localUrl(entry.key))
                }
                PlayerQueue.items = listOf(QueueItem(emptyList(), entry))
                PlayerQueue.index = 0
                ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java)).use {
                    val deadline = SystemClock.elapsedRealtime() + 25_000
                    while (SystemClock.elapsedRealtime() < deadline && (container.history.get(entry.key)?.positionMs ?: 0) < 500) SystemClock.sleep(200)
                    val saved = checkNotNull(container.history.get(entry.key))
                    assertTrue(saved.positionMs >= 500)
                    assertTrue("Playback must use the 12-second local fixture", saved.durationMs in 10_000..15_000)
                }
            } finally {
                container.downloads.remove(entry.key)
                container.history.remove(entry.key)
                PlayerQueue.items = emptyList()
            }
        }
    }

    private fun waitForText(text: String, substring: Boolean = false, timeout: Long = 10_000) {
        compose.waitUntil(timeoutMillis = timeout) {
            compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun saveScreenshot(name: String) {
        // Gradle uninstalls the tested APK on completion; keep diagnostic files outside its data.
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("mkdir -p /sdcard/Download/watchhub-smoke")
        device.executeShellCommand("screencap -p /sdcard/Download/watchhub-smoke/$name.png")
    }
}
