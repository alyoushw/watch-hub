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
import androidx.lifecycle.lifecycleScope
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
import com.alyoushw.watchhub.data.XtreamException
import com.alyoushw.watchhub.data.XtreamKind
import com.alyoushw.watchhub.ui.WatchHubTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen player for the user's Xtream Codes streams: our own controls and gestures
 * (see [PlayerScreen]), resumes where the user stopped, and tries each link in turn.
 *
 * It plays a queue ([PlayerQueue]): the channels of the list it was opened from (left/right
 * switch channel) or a series' episodes ("الحلقة الجاية" near the end, then on by itself).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private val container get() = (application as WatchHubApp).container
    private val history get() = container.history
    private val cloud get() = container.cloud
    private val marks by lazy { SeriesMarks(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val session = PlayerSession()

    private var queue: List<QueueItem> = emptyList()
    private var position = 0
    private var urlIndex = 0
    private var retries = 0
    private var started = false
    private var loadJob: Job? = null

    private val current: QueueItem? get() = queue.getOrNull(position)
    private val entry: WatchEntry? get() = current?.entry

    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = PlayerQueue.items
        position = PlayerQueue.index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        if (queue.isEmpty()) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Providers often redirect a stream to another server, sometimes from https to http
        // (OkHttp follows both). Its pool is dropped when the network changes (see Net).
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.alyoushw.watchhub.data.Net.streams)
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
                val urls = current?.urls.orEmpty()
                val at = if (entry?.isLive == true) 0L else player.currentPosition
                // A dropped connection (network change, a busy server) often comes back: try the
                // same link again a few times, waiting a little longer each time.
                if (retries < RetryDelays.size) {
                    val wait = RetryDelays[retries]
                    retries++
                    if (retries == 1) Toast.makeText(this@PlayerActivity, "انقطع الاتصال، نعيد المحاولة…", Toast.LENGTH_SHORT).show()
                    handler.postDelayed({ if (!isFinishing) setMedia(at) }, wait)
                    return
                }
                retries = 0
                if (urlIndex < urls.lastIndex) {
                    val at = player.currentPosition
                    urlIndex++
                    setMedia(at)
                } else {
                    Toast.makeText(this@PlayerActivity, "ما قدرنا نشغّل «${entry?.title.orEmpty()}»", Toast.LENGTH_LONG).show()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
                if (state == Player.STATE_ENDED) {
                    saveProgress(important = true)
                    // A series goes on to the next episode; anything else closes.
                    if (session.hasNext && entry?.kind == WatchEntry.EPISODE) playAt(position + 1) else finish()
                }
            }

            // A pause is a natural "I stopped here": other devices get the exact spot at once.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && started && player.playbackState == Player.STATE_READY) saveProgress(important = true)
            }
        })

        playAt(position)
        loadSeriesQueue()
        // Wi-Fi ↔ mobile data: the old connection is dead; reconnect at once on the new network.
        lifecycleScope.launch {
            var seen = com.alyoushw.watchhub.data.Net.changes.value
            com.alyoushw.watchhub.data.Net.changes.collect { n ->
                if (n == seen) return@collect
                seen = n
                if (!started) return@collect
                delay(1_000)
                retries = 0
                setMedia(if (entry?.isLive == true) 0L else player.currentPosition)
            }
        }
        lifecycleScope.launch { watchMarkers() }

        // Back while going through one channel's sources: return to the list it came from.
        onBackPressedDispatcher.addCallback(this, filterBack)

        setContent {
            WatchHubTheme(forceDark = true) {
                PlayerScreen(
                    player = player,
                    session = session,
                    onBack = { if (beforeFilter != null) clearFilter() else finish() },
                    onPrevious = { if (position > 0) playAt(position - 1) },
                    onNext = { nextPressed() },
                    onSkipIntro = ::skipIntro,
                    onCancelUpNext = { upNextDismissedFor = entry?.key; session.upNext = null },
                    loadSources = ::otherSources,
                    onPickSource = ::playSource,
                )
            }
        }
    }

    // ---------- moving through the queue ----------

    /** Starts queue item [i]: saves where the current one stopped, then resumes the new one. */
    private fun playAt(i: Int) {
        if (i !in queue.indices) return
        retries = 0
        if (started) saveProgress(important = true)
        position = i
        urlIndex = 0
        started = false
        session.upNext = null
        session.skipIntroLabel = null
        upNextDismissedFor = null
        introPending = null
        val item = queue[i]
        val e = item.entry
        session.title = e.title
        session.subtitle = e.subtitle
        session.live = e.isLive
        session.number = item.number
        session.hasPrevious = i > 0
        session.hasNext = i < queue.lastIndex
        if (e.isLive) preloadIndexes()
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            // Another device may have moved on a moment ago: take the newest spot from the account
            // first (at most ~2 s; offline or signed out, this device's own copy is used).
            cloud.refreshEntry(e.key)
            val saved = history.get(e.key)
            val resumeAt = saved?.takeIf { !it.isLive && !it.finished && it.positionMs > 10_000 }?.positionMs ?: 0L
            history.save(e, important = true)
            setMedia(resumeAt)
            started = true
            if (resumeAt > 0) Toast.makeText(this@PlayerActivity, "نكمل من ${formatTime(resumeAt)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setMedia(startAt: Long) {
        val urls = current?.urls ?: return
        val item = MediaItem.Builder()
            .setUri(urls[urlIndex.coerceIn(0, urls.lastIndex)])
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry?.title).build())
            .build()
        if (startAt > 0) player.setMediaItem(item, startAt) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Opened from "تابع المشاهدة" (one episode only): fetch the series so "الحلقة الجاية" works.
     */
    private fun loadSeriesQueue() {
        val e = entry ?: return
        if (e.kind != WatchEntry.EPISODE || queue.size > 1) return
        val seriesId = e.seriesId ?: return
        val account = container.settings.xtreamAccount(e.accountId) ?: return
        lifecycleScope.launch {
            val series = try {
                container.xtream.series(seriesId, account)
            } catch (x: XtreamException) {
                return@launch
            }
            val items = series.episodes.map { ep ->
                QueueItem(container.xtream.playUrls(ep, account), WatchEntry.episode(account, seriesId, series.name.ifBlank { e.title }, series.cover, ep))
            }
            val at = items.indexOfFirst { it.entry.key == e.key }
            if (at < 0 || entry?.key != e.key) return@launch
            queue = items
            position = at
            session.hasPrevious = at > 0
            session.hasNext = at < items.lastIndex
        }
    }

    private fun nextPressed() {
        val e = entry
        // Pressed in the last minutes: that is where this series' end credits start.
        if (e?.kind == WatchEntry.EPISODE && e.seriesId != null && player.duration > 0) {
            val remaining = player.duration - player.currentPosition
            if (remaining < 240_000) marks.setCredits(seriesKey(e), remaining)
        }
        if (position < queue.lastIndex) playAt(position + 1)
    }

    // ---------- "الحلقة الجاية" and "تخطي المقدمة" ----------

    private var upNextDismissedFor: String? = null

    /** Intro skip waiting to be learned: (series, pressed at, landed at, when). */
    private var introPending: IntroLearn? = null

    private data class IntroLearn(val series: String, val start: Long, var end: Long, val at: Long)

    private fun seriesKey(e: WatchEntry) = "${e.accountId}:${e.seriesId}"

    /** Twice a second: shows / hides the intro and next-episode buttons, counts down. */
    private suspend fun watchMarkers() {
        var countdownTicks = 0
        var lastPos = -1L
        while (true) {
            delay(500)
            val e = entry
            if (!started || e == null || e.kind != WatchEntry.EPISODE || e.seriesId == null) {
                session.skipIntroLabel = null
                session.upNext = null
                lastPos = -1L
                continue
            }
            val pos = player.currentPosition
            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: continue
            val key = seriesKey(e)
            // A jump of more than a few seconds in half a second is a seek by the user.
            val seeked = lastPos >= 0 && kotlin.math.abs(pos - lastPos) > 3_000
            lastPos = pos

            // Intro: where this series' intro was learned, else the first four minutes.
            val learned = marks.intro(key)
            session.skipIntroLabel = when {
                introPending != null -> null
                learned != null && pos in (learned.first - 3_000)..(learned.last - 5_000) -> "تخطي المقدمة"
                learned == null && pos in 5_000..240_000 && dur > 600_000 -> "تخطي المقدمة +٨٥ث"
                else -> null
            }
            // Learning: after a skip, a seek within 30 s corrects where the intro ends; then it is kept.
            introPending?.let { p ->
                if (seeked && pos > p.start && pos - p.start < 600_000) p.end = pos
                if (System.currentTimeMillis() - p.at > 30_000) {
                    marks.setIntro(p.series, p.start, p.end)
                    introPending = null
                }
            }

            // End credits: learned, else the last ~3.5 % (between 30 s and 2 min).
            val credits = marks.credits(key) ?: (dur * 0.035).toLong().coerceIn(30_000, 120_000)
            val remaining = dur - pos
            val next = queue.getOrNull(position + 1)
            if (next != null && dur > 300_000 && remaining in 1..credits && upNextDismissedFor != e.key) {
                if (session.upNext == null) countdownTicks = 0
                session.upNext = next.entry.subtitle ?: next.entry.title
                // Counts only while playing: a pause holds the countdown.
                if (player.isPlaying) countdownTicks++
                val left = 10 - countdownTicks / 2
                session.upNextSeconds = left.coerceAtLeast(0)
                if (left <= 0) playAt(position + 1)
            } else {
                session.upNext = null
            }
        }
    }

    private fun skipIntro() {
        val e = entry ?: return
        val key = seriesKey(e)
        val learned = marks.intro(key)
        val from = player.currentPosition
        val to = learned?.last ?: (from + 85_000)
        player.seekTo(to)
        session.skipIntroLabel = null
        // Watched for 30 s: a seek right after corrects where this series' intro ends.
        introPending = IntroLearn(key, learned?.first ?: from, to, System.currentTimeMillis())
    }

    // ---------- the same channel in other accounts ----------

    /**
     * The channel playing now, from every account: same name and the same quality tags
     * ("MBC 1 4K" finds the 4K ones, "RAW" the RAW ones).
     */
    private suspend fun otherSources(): List<ChannelSource> {
        // While filtered, the sources are the list itself: offer the same ones again.
        val base = beforeFilter?.let { (q, p) -> q[p] } ?: current ?: return emptyList()
        val name = base.channel?.name ?: base.entry.title
        val key = channelKey(name)
        val tags = qualityTags(name)
        val accounts = container.settings.xtreamAccounts.value.filter { it.isComplete }
        return coroutineScope {
            accounts.map { acc ->
                async {
                    val map = try {
                        ChannelIndex.of(container.xtream, acc)
                    } catch (x: XtreamException) {
                        emptyMap()
                    }
                    map[key].orEmpty().filter { qualityTags(it.name) == tags }.map { ch ->
                        ChannelSource(
                            ch.name,
                            acc.label,
                            QueueItem(container.xtream.playUrls(ch, acc), WatchEntry.live(ch, acc), ch.num, ch),
                        )
                    }
                }
            }.awaitAll().flatten().filter { it.item.entry.key != base.entry.key }
        }
    }

    private var preloaded = false

    /** Builds every account's channel index in the background, once, when live TV starts. */
    private fun preloadIndexes() {
        if (preloaded) return
        preloaded = true
        container.settings.xtreamAccounts.value.filter { it.isComplete }.forEach { acc ->
            lifecycleScope.launch { runCatching { ChannelIndex.of(container.xtream, acc) } }
        }
    }

    /** The list and place before choosing a source; set while left/right go through the sources. */
    private var beforeFilter: Pair<List<QueueItem>, Int>? = null
        set(value) {
            field = value
            filterBack.isEnabled = value != null
        }

    private val filterBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearFilter()
    }

    /**
     * Plays [source]; from now on left/right go through the same channel's sources ([all], with
     * the original first) until Back brings the original list back.
     */
    private fun playSource(source: ChannelSource, all: List<ChannelSource>) {
        val base = beforeFilter ?: (queue to position).also { beforeFilter = it }
        val original = base.first[base.second]
        queue = listOf(original) + all.map { it.item }
        session.filter = "نفس القناة: ${queue.size} مصادر · رجوع للقائمة"
        playAt(queue.indexOfFirst { it.entry.key == source.item.entry.key }.coerceAtLeast(0))
    }

    private fun clearFilter() {
        val (list, at) = beforeFilter ?: return
        beforeFilter = null
        val playing = current
        // The source playing now takes the channel's place in the list.
        queue = list.toMutableList().also { if (playing != null) it[at] = playing }
        position = at
        session.filter = null
        session.number = queue[at].number
        session.hasPrevious = at > 0
        session.hasNext = at < queue.lastIndex
        Toast.makeText(this, "رجعنا لقائمة القنوات", Toast.LENGTH_SHORT).show()
    }

    // ---------- saving ----------

    private fun saveProgress(important: Boolean = false) {
        val e = entry ?: return
        if (e.isLive || !started) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        history.save(e.copy(positionMs = player.currentPosition.coerceAtLeast(0), durationMs = duration), important)
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
            saveProgress(important = true)
            player.pause()
        }
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        private val RetryDelays = longArrayOf(1_500, 3_000, 6_000, 10_000)

        /** Plays [urls] (tried in order); [entry] names it and keeps its place in the watch history. */
        fun start(context: Context, urls: List<String>, entry: WatchEntry) =
            start(context, listOf(QueueItem(urls, entry)), 0)

        /** Plays [items] starting at [index]: left/right (channels) or next episode move through them. */
        fun start(context: Context, items: List<QueueItem>, index: Int) {
            if (items.isEmpty() || items.getOrNull(index)?.urls.isNullOrEmpty()) return
            PlayerQueue.items = items
            PlayerQueue.index = index
            context.startActivity(Intent(context, PlayerActivity::class.java))
        }
    }
}
