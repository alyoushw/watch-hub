package com.alyoushw.watchhub.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.alyoushw.watchhub.WatchHubApp
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.ui.WatchHubTheme
import kotlinx.serialization.json.Json

/**
 * Full-screen player for the user's Xtream Codes streams: our own controls and gestures
 * (see [PlayerScreen]), resumes where the user stopped, and tries each link in turn.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var urls: List<String> = emptyList()
    private var index = 0
    private var entry: WatchEntry? = null
    private val handler = Handler(Looper.getMainLooper())
    private val history get() = (application as WatchHubApp).container.history

    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        entry = intent.getStringExtra(EXTRA_ENTRY)?.let { runCatching { Json.decodeFromString(WatchEntry.serializer(), it) }.getOrNull() }
        if (urls.isEmpty()) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Providers often redirect a stream to another server, sometimes from https to http.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // Arabic audio and subtitles first when a file has them.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("ar")
            .setPreferredTextLanguages("ar")
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (index < urls.lastIndex) {
                    val at = player.currentPosition
                    index++
                    load(at)
                } else {
                    Toast.makeText(this@PlayerActivity, "ما قدرنا نشغّل «${entry?.title.orEmpty()}»", Toast.LENGTH_LONG).show()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    saveProgress()
                    finish()
                }
            }
        })

        val e = entry
        val saved = e?.let { history.get(it.key) }
        val resumeAt = saved?.takeIf { !it.isLive && !it.finished && it.positionMs > 10_000 }?.positionMs ?: 0L
        e?.let { history.save(it) }
        load(resumeAt)
        if (resumeAt > 0) Toast.makeText(this, "نكمل من حيث وقفت", Toast.LENGTH_SHORT).show()

        setContent {
            WatchHubTheme {
                PlayerScreen(
                    player = player,
                    title = e?.title.orEmpty(),
                    subtitle = e?.subtitle,
                    live = e?.isLive == true,
                    onBack = ::finish,
                )
            }
        }
    }

    private fun load(startAt: Long) {
        val item = MediaItem.Builder()
            .setUri(urls[index])
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry?.title).build())
            .build()
        if (startAt > 0) player.setMediaItem(item, startAt) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    private fun saveProgress() {
        val e = entry ?: return
        if (e.isLive) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        history.save(e.copy(positionMs = player.currentPosition.coerceAtLeast(0), durationMs = duration))
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) {
            player.playWhenReady = true
            handler.postDelayed(saver, 5_000)
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(saver)
        if (::player.isInitialized) {
            saveProgress()
            player.pause()
        }
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_ENTRY = "entry"

        /** Plays [urls] (tried in order); [entry] names it and keeps its place in the watch history. */
        fun start(context: Context, urls: List<String>, entry: WatchEntry) {
            if (urls.isEmpty()) return
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                    .putExtra(EXTRA_ENTRY, Json.encodeToString(WatchEntry.serializer(), entry)),
            )
        }
    }
}
