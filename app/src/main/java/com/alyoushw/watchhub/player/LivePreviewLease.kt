package com.alyoushw.watchhub.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Moves ownership of a live connection; no media item or subscription credentials in Intents. */
@androidx.annotation.OptIn(UnstableApi::class)
class LivePreviewLease(val player: ExoPlayer, val http: OkHttpDataSource.Factory) {
    var key: String? = null
    var view: PlayerView? = null
    var transferred = false
        private set

    fun readyFor(key: String) = !transferred && this.key == key && player.currentMediaItem != null &&
        player.playerError == null && player.playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING)

    companion object {
        private val handler = Handler(Looper.getMainLooper())
        private var pending: Pair<String, LivePreviewLease>? = null
        fun offer(lease: LivePreviewLease): String {
            pending?.second?.player?.release()
            val token = java.util.UUID.randomUUID().toString()
            lease.transferred = true
            lease.view?.player = null
            pending = token to lease
            handler.postDelayed({ if (pending?.first == token) { pending = null; lease.player.release() } }, 10_000)
            return token
        }
        fun take(token: String?, key: String?): LivePreviewLease? {
            val value = pending ?: return null
            if (token != value.first || key != value.second.key) return null
            pending = null
            return value.second
        }
        fun cancel(token: String) {
            val value = pending?.takeIf { it.first == token } ?: return
            pending = null
            value.second.transferred = false
            value.second.view?.player = value.second.player
        }
    }
}
