package com.alyoushw.watchhub

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class AnimeFlowSmokeTest {
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
        try {
            device.executeShellCommand("wm size 1312x1884")
            device.executeShellCommand("wm density 320")
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            // Resize/rotation are asynchronous; wait before attaching the test UI.
            val displayDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < displayDeadline && device.displayWidth < 1800) SystemClock.sleep(100)
            device.waitForIdle(2_000)
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
                    assertTrue(device.wait(Until.hasObject(By.text("تشغيل أنمي فاي")), 10_000) == true)
                    clickAccessibleText("تشغيل أنمي فاي")
                    instrumentation.waitForIdleSync()
                    assertTrue("The watch button must invoke its navigation callback", opened.get())
                    assertTrue("Navigation must show the episode page", device.wait(Until.hasObject(By.textContains("تنزيل الحلقات للمشاهدة بدون نت")), 10_000) == true)
                    assertTrue("The watch action must navigate to episode cards", device.wait(Until.hasObject(By.textContains("S01 E01")), 30_000) == true)
                    assertTrue(device.hasObject(By.textContains("تنزيل الحلقات للمشاهدة بدون نت")))
                    device.waitForIdle(2_000)
                    saveScreenshot("animeify-episodes")
                    clickAccessibleText("تنزيل الحلقات للمشاهدة بدون نت")
                    assertTrue(device.wait(Until.hasObject(By.textContains("تنزيل الموسم")), 10_000) == true)
                    assertTrue(device.wait(Until.hasObject(By.textContains("MediaFire")), 30_000) == true)
                    saveScreenshot("animeify-download-picker")
                } catch (failure: Throwable) {
                    saveScreenshot("animeify-flow-failure")
                    val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                    device.dumpWindowHierarchy(File(directory, "animeify-flow-failure.xml"))
                    throw failure
                }
            }
        } finally {
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
            device.executeShellCommand("wm size reset")
            device.executeShellCommand("wm density reset")
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

    /** Exercise the real Compose click action without stale coordinates after display changes. */
    private fun clickAccessibleText(text: String) {
        var node = instrumentation.uiAutomation.rootInActiveWindow
            .findAccessibilityNodeInfosByText(text).firstOrNull()
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("No clickable accessibility action for $text",
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun saveScreenshot(name: String) {
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
