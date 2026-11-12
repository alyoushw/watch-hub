package com.alyoushw.watchhub

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alyoushw.watchhub.data.AnimeEpisodeRef
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.ui.ContinueWatchingRow
import com.alyoushw.watchhub.ui.WatchHubTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TvControlsSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as WatchHubApp).container

    @Test fun heldRemoteKeyOpensContinueMenuWithoutSelectingItsFirstOption() {
        val opened = AtomicInteger()
        val title = "اختبار قائمة متابعة الحلقة"
        val entry = WatchEntry("test:remote:continue", WatchEntry.ANIME, "1", title,
            seriesId = "remote-fixture", positionMs = 9_900, durationMs = 10_000,
            animeRef = AnimeEpisodeRef("remote-fixture", 20, "SERIES", 1, "1", "FRLink"))
        container.history.save(entry)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    WatchHubTheme(forceDark = true) {
                        ContinueWatchingRow(onOpenSeries = {}, kinds = setOf(WatchEntry.ANIME),
                            onOpenAnime = { opened.incrementAndGet() })
                    }
                } }
                instrumentation.setInTouchMode(false)
                compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                compose.onNodeWithText(title).assertIsFocused()
                val downAt = SystemClock.uptimeMillis()
                send(downAt, KeyEvent.ACTION_DOWN, 0)
                send(downAt, KeyEvent.ACTION_DOWN, 1)
                send(downAt, KeyEvent.ACTION_DOWN, 2)
                compose.waitForIdle()
                compose.onNodeWithText("المواسم والحلقات · أنمي فاي").assertDoesNotExist()
                assertEquals(0, opened.get())
                send(downAt, KeyEvent.ACTION_UP, 0)
                compose.waitForIdle()
                assertEquals("Releasing the long press must not activate the title", 0, opened.get())
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("المواسم والحلقات · أنمي فاي").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals("The originating key must not select a dialog action", 0, opened.get())
                compose.onNodeWithText("المواسم والحلقات · أنمي فاي")
                    .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                val nextDown = SystemClock.uptimeMillis()
                send(nextDown, KeyEvent.ACTION_DOWN, 0)
                send(nextDown, KeyEvent.ACTION_UP, 0)
                compose.waitForIdle()
                assertEquals("A fresh short press must select exactly once", 1, opened.get())
                compose.onNodeWithText("المواسم والحلقات · أنمي فاي").assertDoesNotExist()
            }
        } finally { container.history.remove(entry.key) }
    }

    private fun send(downAt: Long, action: Int, repeats: Int) {
        instrumentation.sendKeySync(KeyEvent(downAt, SystemClock.uptimeMillis(), action,
            KeyEvent.KEYCODE_DPAD_CENTER, repeats, 0))
    }
}
