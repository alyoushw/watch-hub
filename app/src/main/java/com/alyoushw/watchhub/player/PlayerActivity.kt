package com.alyoushw.watchhub.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Full-screen player for the user's Xtream Codes streams. Tries each link in turn until one plays. */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var urls: List<String> = emptyList()
    private var index = 0
    private var title: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
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

        val view = PlayerView(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(view)

        // Providers often redirect a stream to another server, sometimes from https to http.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (index < urls.lastIndex) {
                    index++
                    play(p)
                } else {
                    Toast.makeText(this@PlayerActivity, "ما قدرنا نشغّل «$title»", Toast.LENGTH_LONG).show()
                }
            }
        })
        view.player = p
        player = p
        play(p)
    }

    private fun play(p: ExoPlayer) {
        val item = MediaItem.Builder()
            .setUri(urls[index])
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, urls: List<String>, title: String) {
            if (urls.isEmpty()) return
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                    .putExtra(EXTRA_TITLE, title),
            )
        }
    }
}
