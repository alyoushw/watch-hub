package com.alyoushw.watchhub

import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.alyoushw.watchhub.data.*
import com.alyoushw.watchhub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceFlowSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun watchActionOpensSeasonEpisodePageAndQualityDialogWithoutAutoplay() {
        val lookup = SourceLookup(SourceQuery(MediaType.TV, 99999997, "Owned fixture series", 2025, 1, 1),
            "Owned fixture series", "قصة اختبار لصفحة المواسم والحلقات", seasons = listOf(SourceSeason(1, 3), SourceSeason(2, 2)))
        val provider = object : WatchSourceProvider {
            override suspend fun search(query: SourceQuery) = listOf(WatchSource("fixture", "owner", "Fixture provider", query,
                listOf(SourceVariant("hd", "Owned fixture 1080p", "https://example.org/owned.mp4", SourceTransport.MP4)),
                attribution = "Owned fixture", licenceUrl = "https://example.org/rights"))
        }
        val sources = WatchSources({ listOf(provider) }, SourceProbe { true })
        var selected: WatchSourceRef? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                var opened by remember { mutableStateOf(false) }
                WatchHubTheme(forceDark = true) { Surface {
                    if (!opened) SourceWatchButton(lookup, onOpen = { opened = true })
                    else SourceWatchScreen(lookup, onBack = { opened = false }, sources = sources, onPlay = { selected = it })
                } }
            } }
            compose.onNodeWithText("تشغيل تورنت").performClick()
            compose.onNodeWithTag("source-episodes", useUnmergedTree = true).performScrollToNode(hasText("S01 E03", substring = true))
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.executeShellCommand("mkdir -p /sdcard/Download/watchhub-smoke")
            device.executeShellCommand("screencap -p /sdcard/Download/watchhub-smoke/torrent-seasons.png")
            compose.onNode(hasText("S01 E03", substring = true)).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Fixture provider · FHD · 1080p").fetchSemanticsNodes().isNotEmpty() }
            assertNull("Opening an episode must wait for explicit source selection", selected)
            device.executeShellCommand("screencap -p /sdcard/Download/watchhub-smoke/torrent-quality-dialog.png")
            compose.onNodeWithText("Fixture provider · FHD · 1080p").performClick()
            assertEquals(3, selected?.query?.episode)
            assertEquals(1, selected?.query?.season)
            compose.onNodeWithTag("source-episodes", useUnmergedTree = true).performScrollToNode(hasText("الموسم 2"))
            compose.onNodeWithText("الموسم 2").performClick()
            compose.onNodeWithTag("source-episodes", useUnmergedTree = true).performScrollToNode(hasText("S02 E02", substring = true))
            compose.onNode(hasText("S02 E02", substring = true)).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Fixture provider · FHD · 1080p").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Fixture provider · FHD · 1080p").performClick()
            assertEquals(2, selected?.query?.season)
            assertEquals(2, selected?.query?.episode)
        }
    }
}
