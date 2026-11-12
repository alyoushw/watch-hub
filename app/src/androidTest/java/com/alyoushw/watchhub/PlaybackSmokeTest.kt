package com.alyoushw.watchhub

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.PlayerQueue
import com.alyoushw.watchhub.player.QueueItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackSmokeTest {
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
