package com.alyoushw.watchhub.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.alyoushw.watchhub.data.isTvDevice
import com.alyoushw.watchhub.ui.FocusColor
import com.alyoushw.watchhub.ui.Gold
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

private enum class Menu { SUBTITLES, AUDIO, SPEED, SOURCES, INFO, SLEEP, PICTURE, QUALITY }

/** Picture modes for "حجم الصورة": how the video fits, or a fixed shape it is drawn in. */
private data class PictureMode(val label: String, val resize: Int, val ratio: Float? = null)

@androidx.annotation.OptIn(UnstableApi::class)
private val PictureModes = listOf(
    PictureMode("ملاءمة الشاشة (بدون قص)", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    PictureMode("ملء الشاشة مع قص الأطراف", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    PictureMode("تمديد على كامل الشاشة", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    PictureMode("ملء العرض", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
    PictureMode("ملء الطول", AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT),
    PictureMode("16:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 16f / 9f),
    PictureMode("18:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 2f),
    PictureMode("21:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 21f / 9f),
    PictureMode("2.35:1 (سينما)", AspectRatioFrameLayout.RESIZE_MODE_FILL, 2.35f),
    PictureMode("4:3", AspectRatioFrameLayout.RESIZE_MODE_FILL, 4f / 3f),
)

private val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Subtitle sizes (fraction of the picture height). */
private val SubSizes = listOf(0.05f to "صغير", 0.065f to "متوسط", 0.08f to "كبير", 0.1f to "كبير جداً")

/** Sleep timer choices in minutes; -1 = at the end of this episode / film. */
private val SleepChoices = listOf(15, 30, 45, 60, 90, -1)

/** What a gesture or key is changing right now, shown in the middle of the screen. */
private data class Indicator(val icon: ImageVector, val text: String, val level: Float? = null)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    player: ExoPlayer,
    session: PlayerSession,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkipIntro: () -> Unit,
    onCancelUpNext: () -> Unit,
    loadSources: suspend () -> List<ChannelSource>,
    onPickSource: (ChannelSource, List<ChannelSource>) -> Unit,
    /** Starts the player again (after switching the sound decoder). */
    onRestart: () -> Unit = {},
    onPreload: (Int) -> Unit = {},
    onAutoPreload: () -> Unit = {},
    onManualPreload: () -> Unit = {},
    onDismissSlow: () -> Unit = {},
    onPlayNow: () -> Unit = {},
) {
    val title = session.title
    val subtitle = session.subtitle
    val live = session.live
    val context = LocalContext.current
    val activity = context as Activity
    val tv = remember { context.isTvDevice }
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val screenRatio = config.screenWidthDp.toFloat() / config.screenHeightDp.coerceAtLeast(1)
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Menu?>(null) }
    // Picture size, shape, zoom and stretch: kept on this device (a car screen keeps its own).
    val picPrefs = remember { context.getSharedPreferences("player", Context.MODE_PRIVATE) }
    var resize by remember { mutableIntStateOf(picPrefs.getInt("picture", 0).coerceIn(0, PictureModes.lastIndex)) }
    var zoom by remember { mutableFloatStateOf(picPrefs.getFloat("zoom", 1f).coerceIn(0.5f, 4f)) }
    var stretch by remember { mutableFloatStateOf(picPrefs.getFloat("stretch", 1f).coerceIn(0.5f, 2f)) }
    fun savePicture() {
        picPrefs.edit().putInt("picture", resize).putFloat("zoom", zoom).putFloat("stretch", stretch).apply()
    }
    var indicator by remember { mutableStateOf<Indicator?>(null) }
    var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var speed by remember { mutableFloatStateOf(1f) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    val quality = info?.quality
    val prefs = remember { context.getSharedPreferences("player", Context.MODE_PRIVATE) }
    var subSize by remember { mutableIntStateOf(prefs.getInt("subSize", 1).coerceIn(0, SubSizes.lastIndex)) }
    var subBackground by remember { mutableStateOf(prefs.getBoolean("subBg", false)) }
    var boost by remember { mutableStateOf(false) }
    /** Sleep timer: when to stop (ms clock), or stop at the end of this item. */
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    var sleepAtEnd by remember { mutableStateOf(false) }
    var sleepLeft by remember { mutableStateOf<String?>(null) }
    var channelBanner by remember { mutableStateOf(false) }

    // Live: a channel change shows its number and name for a moment.
    LaunchedEffect(title, live) {
        if (live) {
            channelBanner = true
            delay(3_000)
            channelBanner = false
        } else {
            channelBanner = false
        }
    }

    fun poke() {
        touchedAt = System.currentTimeMillis()
    }

    fun flash(i: Indicator) {
        indicator = i
        poke()
    }

    fun seekBy(ms: Long) {
        if (live) return
        val target = (player.currentPosition + ms).coerceIn(0, (player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        player.seekTo(target)
        flash(Indicator(if (ms < 0) PlayerIcons.Replay else PlayerIcons.Forward, (if (ms < 0) "-" else "+") + "${abs(ms) / 1000} ث"))
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        poke()
    }

    fun switchChannel(forward: Boolean) {
        if (forward && session.hasNext) onNext() else if (!forward && session.hasPrevious) onPrevious()
        poke()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Clock for the seek bar.
    LaunchedEffect(player) {
        var frames = -1
        var framesAt = 0L
        var measured = 0f
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            // Picture size from the stream; frame rate from the stream, else counted from
            // the frames actually shown.
            val counters = player.videoDecoderCounters
            val now = System.currentTimeMillis()
            if (counters != null) {
                counters.ensureUpdated()
                val n = counters.renderedOutputBufferCount
                if (frames >= 0 && n >= frames && now - framesAt >= 1_500) {
                    val fps = (n - frames) * 1000f / (now - framesAt)
                    measured = if (player.isPlaying && fps > 1f) fps else measured
                    frames = n
                    framesAt = now
                } else if (frames < 0 || n < frames) {
                    frames = n
                    framesAt = now
                }
            }
            val t = player.currentTracks.groups
            info = mediaInfo(
                player,
                measured,
                audioTracks = t.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length },
                textTracks = t.filter { it.type == C.TRACK_TYPE_TEXT }.sumOf { it.length },
            )
            // Sleep timer.
            val stopAt = sleepAt
            if (stopAt != null) {
                val left = stopAt - now
                sleepLeft = if (left > 0) "${(left + 59_999) / 60_000} د" else null
                if (left <= 0) {
                    sleepAt = null
                    player.pause()
                    onBack()
                }
            } else if (sleepAtEnd) {
                sleepLeft = "نهاية"
                val total = player.duration
                if (session.upNext != null) onCancelUpNext()
                if (total > 0 && total - player.currentPosition < 1_500) {
                    sleepAtEnd = false
                    player.pause()
                    onBack()
                }
            } else {
                sleepLeft = null
            }
            delay(500)
        }
    }

    // "تعزيز الصوت": louder speech for quiet films (follows the player's audio session).
    DisposableEffect(boost, player.audioSessionId) {
        val enhancer = if (boost) {
            runCatching {
                android.media.audiofx.LoudnessEnhancer(player.audioSessionId).apply {
                    setTargetGain(900)
                    enabled = true
                }
            }.getOrNull()
        } else {
            null
        }
        onDispose { runCatching { enhancer?.release() } }
    }

    // Controls fade out after a few seconds of playing untouched.
    LaunchedEffect(controls, touchedAt, playing, menu) {
        if (controls && playing && menu == null) {
            delay(4_000)
            controls = false
        }
    }
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(900)
            indicator = null
        }
    }

    BackHandler(enabled = menu != null || (tv && controls) || session.upNext != null) {
        when {
            menu != null -> menu = null
            session.upNext != null && !controls -> onCancelUpNext()
            else -> controls = false
        }
    }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(controls, menu) {
        // TV: with the controls hidden the remote talks to the player; shown, it lands on play/pause.
        runCatching { if (controls && menu == null) playFocus.requestFocus() else if (!controls) rootFocus.requestFocus() }
    }

    // Brightness starts from the screen's own setting.
    var brightness by remember {
        mutableFloatStateOf(activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f)
    }
    fun setBrightness(v: Float) {
        brightness = v.coerceIn(0.02f, 1f)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
        flash(Indicator(PlayerIcons.Brightness, "الإضاءة ${(brightness * 100).toInt()}%", brightness))
    }
    fun setVolume(v: Float) {
        val level = (v.coerceIn(0f, 1f) * maxVolume).toInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        flash(Indicator(PlayerIcons.Volume, "الصوت ${level * 100 / maxVolume}%", level.toFloat() / maxVolume))
    }

    // Player UI is laid out left to right (timeline, gestures), with Arabic text inside.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // The slow-connection card and the loading-ahead box take the remote while shown.
                    if ((session.slowNet != null || session.preloading != null) && !controls && menu == null) return@onPreviewKeyEvent false
                    poke()
                    when (e.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            togglePlay()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            seekBy(30_000)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            seekBy(-30_000)
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            if (live) switchChannel(true) else if (session.hasNext) onNext()
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (live) switchChannel(false) else if (session.hasPrevious) onPrevious()
                            true
                        }
                        // Controls hidden: left/right switch channel (live) or skip 10 s.
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controls && !locked && menu == null) {
                            val right = e.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                            if (live) switchChannel(right) else seekBy(if (right) 10_000 else -10_000)
                            true
                        } else {
                            false
                        }
                        // Controls hidden: OK answers the card on screen (next episode, skip intro).
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controls && menu == null) {
                            when {
                                session.upNext != null -> onNext()
                                session.skipIntroLabel != null -> onSkipIntro()
                                else -> controls = true
                            }
                            true
                        } else {
                            false
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!controls) {
                            controls = true
                            true
                        } else {
                            false
                        }
                        else -> false
                    }
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                        setKeepContentOnPlayerReset(true)
                        subtitleView?.setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null,
                            ),
                        )
                        subtitleView?.setFractionalTextSize(0.065f)
                    }
                },
                update = { view ->
                    view.resizeMode = PictureModes[resize].resize
                    view.subtitleView?.setFractionalTextSize(SubSizes[subSize].first)
                    view.subtitleView?.setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            if (subBackground) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            if (subBackground) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null,
                        ),
                    )
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(
                        PictureModes[resize].ratio?.let { r ->
                            Modifier.aspectRatio(r, matchHeightConstraintsFirst = screenRatio > r)
                        } ?: Modifier.fillMaxSize(),
                    )
                    .graphicsLayer {
                        scaleX = zoom * stretch
                        scaleY = zoom
                    },
            )

            // Touch: tap shows/hides, double tap skips, drag left/right edge for brightness/volume,
            // drag sideways to seek, pinch to zoom.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(locked, live) {
                        detectTapGestures(
                            onTap = {
                                controls = !controls
                                poke()
                            },
                            onDoubleTap = { at ->
                                if (locked) return@detectTapGestures
                                when {
                                    at.x < size.width / 3f -> seekBy(-10_000)
                                    at.x > size.width * 2f / 3f -> seekBy(10_000)
                                    else -> togglePlay()
                                }
                            },
                        )
                    }
                    .pointerInput(locked, live) {
                        if (locked) return@pointerInput
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var mode = 0 // 1 seek, 2 brightness, 3 volume, 4 pinch, -1 ignore
                            val startPos = player.currentPosition
                            val startBright = brightness
                            val startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                            var startZoom = zoom
                            var startDist = 0f
                            var target = startPos
                            var swipeDx = 0f
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    val d = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                                    if (mode != 4) {
                                        mode = 4
                                        startDist = d
                                        startZoom = zoom
                                    } else if (startDist > 0) {
                                        zoom = (startZoom * d / startDist).coerceIn(0.5f, 4f)
                                        flash(Indicator(PlayerIcons.Aspect, "تكبير ×" + String.format(Locale.US, "%.1f", zoom)))
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (pressed.size == 1 && mode != 4) {
                                    val c = pressed[0]
                                    val dx = c.position.x - down.position.x
                                    val dy = c.position.y - down.position.y
                                    if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                                        mode = when {
                                            abs(dx) > abs(dy) -> if (live) 5 else 1
                                            down.position.x < size.width / 2f -> 2
                                            else -> 3
                                        }
                                    }
                                    when (mode) {
                                        1 -> {
                                            val total = player.duration.takeIf { it > 0 } ?: 0L
                                            val span = if (total > 0) minOf(total, 180_000L) else 120_000L
                                            target = (startPos + (dx / size.width * span).toLong()).coerceIn(0, if (total > 0) total else Long.MAX_VALUE)
                                            seekPreview = target
                                            val delta = (target - startPos) / 1000
                                            flash(Indicator(if (delta < 0) PlayerIcons.Replay else PlayerIcons.Forward, "${if (delta < 0) "-" else "+"}${formatTime(abs(delta) * 1000)}  ·  ${formatTime(target)}"))
                                        }
                                        2 -> setBrightness(startBright - dy / size.height * 1.3f)
                                        3 -> setVolume(startVol - dy / size.height * 1.3f)
                                        5 -> swipeDx = dx
                                    }
                                    if (mode > 0) c.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            if (mode == 1) player.seekTo(target)
                            // Live: a swipe of a fifth of the screen changes channel (to the left = next).
                            if (mode == 5 && abs(swipeDx) > size.width / 5f) switchChannel(swipeDx < 0)
                            seekPreview = null
                        }
                    },
            )

            if (buffering) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Gold, strokeWidth = 4.dp)
            }

            indicator?.let { ind ->
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(ind.icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        Text(ind.text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    ind.level?.let {
                        LinearProgressIndicator(
                            progress = { it },
                            modifier = Modifier.width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Gold,
                            trackColor = Color.White.copy(alpha = 0.25f),
                        )
                    }
                }
            }

            AnimatedVisibility(live && channelBanner && !controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
                ChannelBanner(session.number, title, session.filter ?: quality, session.hasPrevious, session.hasNext)
            }

            session.skipIntroLabel?.let { label ->
                if (!locked && session.upNext == null) {
                    PillButton(
                        label + if (tv && !controls) "  (OK)" else "",
                        Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (controls) 110.dp else 32.dp),
                        onClick = onSkipIntro,
                    )
                }
            }

            session.slowNet?.let { why ->
                if (!locked && session.preloading == null) {
                    SlowNetCard(
                        why = why,
                        auto = session.autoPreload,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = if (controls) 84.dp else 20.dp),
                        onPreload = onPreload,
                        onAuto = onAutoPreload,
                        onDismiss = onDismissSlow,
                    )
                }
            }
            session.preloading?.let { (ready, wanted) ->
                PreloadBox(ready, wanted, Modifier.align(Alignment.Center), onPlayNow)
            }

            session.upNext?.let { next ->
                if (!locked) {
                    UpNextCard(
                        next = next,
                        seconds = session.upNextSeconds,
                        tvHint = tv && !controls,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (controls) 110.dp else 32.dp),
                        onPlay = onNext,
                        onCancel = onCancelUpNext,
                    )
                }
            }

            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                if (locked) {
                    Box(Modifier.fillMaxSize()) {
                        RoundButton(PlayerIcons.Lock, "افتح القفل", Modifier.align(Alignment.CenterStart).padding(24.dp)) {
                            locked = false
                            flash(Indicator(PlayerIcons.Lock, "انفتح القفل"))
                        }
                    }
                } else {
                    Controls(
                        title = title,
                        subtitle = subtitle,
                        live = live,
                        number = session.number,
                        filter = session.filter,
                        badges = info?.badges.orEmpty(),
                        sleepLeft = sleepLeft,
                        hasPrevious = session.hasPrevious,
                        hasNext = session.hasNext,
                        onPrevious = { if (live) switchChannel(false) else onPrevious() },
                        onNext = { if (live) switchChannel(true) else onNext() },
                        playing = playing,
                        position = seekPreview ?: position,
                        duration = duration,
                        speed = speed,
                        resizeLabel = PictureModes[resize].label,
                        playFocus = playFocus,
                        onBack = onBack,
                        onPlayPause = ::togglePlay,
                        onSeekBy = ::seekBy,
                        onSeekTo = {
                            player.seekTo(it)
                            poke()
                        },
                        onMenu = {
                            menu = it
                            poke()
                        },
                        onResize = {
                            menu = Menu.PICTURE
                            poke()
                        },
                        onLock = {
                            locked = true
                            controls = false
                            flash(Indicator(PlayerIcons.Lock, "الشاشة مقفلة: اضغط ثم القفل للفتح"))
                        },
                        onInteract = ::poke,
                    )
                }
            }

            if (menu == Menu.SOURCES) {
                SourcesPanel(
                    title = title,
                    load = loadSources,
                    onClose = { menu = null },
                    onPick = { src, all ->
                        menu = null
                        onPickSource(src, all)
                    },
                )
            } else if (menu == Menu.INFO) {
                InfoPanel(info, onClose = { menu = null })
            } else if (menu == Menu.QUALITY) {
                QualityPanel(
                    autoPreload = session.autoPreload,
                    onAutoPreload = {
                        menu = null
                        onAutoPreload()
                    },
                    onManualPreload = { menu = null; onManualPreload() },
                    tracks = tracks,
                    auto = player.trackSelectionParameters.overrides.keys.none { it.type == C.TRACK_TYPE_VIDEO },
                    onAuto = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                        flash(Indicator(PlayerIcons.Quality, "الجودة: تلقائي"))
                        menu = null
                    },
                    onPick = { group, i, label ->
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        flash(Indicator(PlayerIcons.Quality, "الجودة: $label"))
                        menu = null
                    },
                    onClose = { menu = null },
                )
            } else if (menu == Menu.PICTURE) {
                PicturePanel(
                    mode = resize,
                    zoom = zoom,
                    stretch = stretch,
                    onMode = {
                        resize = it
                        savePicture()
                    },
                    onZoom = {
                        zoom = it.coerceIn(0.5f, 4f)
                        savePicture()
                    },
                    onStretch = {
                        stretch = it.coerceIn(0.5f, 2f)
                        savePicture()
                    },
                    onReset = {
                        resize = 0
                        zoom = 1f
                        stretch = 1f
                        savePicture()
                    },
                    onClose = { menu = null },
                )
            } else menu?.let { m ->
                MenuPanel(
                    menu = m,
                    tracks = tracks,
                    speed = speed,
                    live = live,
                    subSize = subSize,
                    onSubSize = {
                        subSize = it
                        prefs.edit().putInt("subSize", it).apply()
                    },
                    subBackground = subBackground,
                    onSubBackground = {
                        subBackground = !subBackground
                        prefs.edit().putBoolean("subBg", subBackground).apply()
                    },
                    boost = boost,
                    softwareAudio = remember { prefs.getBoolean("softwareAudio", false) },
                    onSoftwareAudio = {
                        prefs.edit().putBoolean("softwareAudio", !prefs.getBoolean("softwareAudio", false)).commit()
                        menu = null
                        onRestart()
                    },
                    onBoost = {
                        boost = !boost
                        flash(Indicator(PlayerIcons.Volume, if (boost) "تعزيز الصوت شغّال" else "تعزيز الصوت طافي"))
                    },
                    sleeping = sleepAt != null || sleepAtEnd,
                    onSleep = { minutes ->
                        when (minutes) {
                            null -> {
                                sleepAt = null
                                sleepAtEnd = false
                            }
                            -1 -> {
                                sleepAt = null
                                sleepAtEnd = true
                            }
                            else -> {
                                sleepAtEnd = false
                                sleepAt = System.currentTimeMillis() + minutes * 60_000L
                            }
                        }
                        flash(Indicator(PlayerIcons.Sleep, when (minutes) {
                            null -> "انلغى مؤقت النوم"
                            -1 -> "يوقف بنهاية ${if (live) "البث" else "الحلقة"}"
                            else -> "يوقف بعد $minutes دقيقة"
                        }))
                        menu = null
                    },
                    onClose = { menu = null },
                    onPickTrack = { group, i ->
                        val type = group.type
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(type, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        menu = null
                    },
                    onSubtitlesOff = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        menu = null
                    },
                    onSpeed = {
                        speed = it
                        player.setPlaybackSpeed(it)
                        menu = null
                    },
                )
            }
        }
    }
}

@Composable
private fun Controls(
    title: String,
    subtitle: String?,
    live: Boolean,
    number: Int?,
    filter: String?,
    badges: List<String>,
    sleepLeft: String?,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    playing: Boolean,
    position: Long,
    duration: Long,
    speed: Float,
    resizeLabel: String,
    playFocus: FocusRequester,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onMenu: (Menu) -> Unit,
    onResize: () -> Unit,
    onLock: () -> Unit,
    onInteract: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Top: back, title, tools.
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", onBack)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    (number?.let { "$it · " } ?: "") + title,
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                filter?.let { Text(it, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
            if (live) ToolButton(PlayerIcons.Sources, "نفس القناة في حسابات ثانية") { onMenu(Menu.SOURCES) }
            ToolButton(PlayerIcons.Subtitles, "الترجمة") { onMenu(Menu.SUBTITLES) }
            ToolButton(PlayerIcons.Quality, "الجودة") { onMenu(Menu.QUALITY) }
            ToolButton(PlayerIcons.Audio, "الصوت المعلّق") { onMenu(Menu.AUDIO) }
            ToolButton(PlayerIcons.Aspect, resizeLabel, onResize)
            if (!live) ToolButton(PlayerIcons.Speed, if (speed == 1f) "السرعة" else "×$speed") { onMenu(Menu.SPEED) }
            ToolButton(PlayerIcons.Sleep, sleepLeft?.let { "مؤقت النوم: $it" } ?: "مؤقت النوم") { onMenu(Menu.SLEEP) }
            sleepLeft?.let { Text(it, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            ToolButton(PlayerIcons.Info, "معلومات التشغيل") { onMenu(Menu.INFO) }
            ToolButton(PlayerIcons.Lock, "قفل الشاشة", onLock)
        }

        // Middle: skip back, play/pause, skip forward.
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            if (live && hasPrevious) RoundButton(PlayerIcons.Previous, "القناة السابقة", onClick = onPrevious)
            if (!live) RoundButton(PlayerIcons.Replay, "رجوع 10 ثواني", label = "10") { onSeekBy(-10_000) }
            RoundButton(
                if (playing) PlayerIcons.Pause else Icons.Filled.PlayArrow,
                if (playing) "إيقاف مؤقت" else "تشغيل",
                Modifier.focusRequester(playFocus),
                big = true,
                onClick = onPlayPause,
            )
            if (!live) RoundButton(PlayerIcons.Forward, "تقديم 10 ثواني", label = "10") { onSeekBy(10_000) }
            if (live && hasNext) RoundButton(PlayerIcons.Next, "القناة الجاية", onClick = onNext)
        }

        // Bottom: timeline, or the live badge.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            if (live) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "● مباشر",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE5484D)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    badges.forEach { QualityBadge(it) }
                    Spacer(Modifier.weight(1f))
                    Text("◀ ▶ لتغيير القناة", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            } else {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val fraction = if (duration > 0) position.toFloat() / duration else 0f
                Slider(
                    value = dragging ?: fraction,
                    onValueChange = {
                        dragging = it
                        onInteract()
                    },
                    onValueChangeFinished = {
                        dragging?.let { onSeekTo((it * duration).toLong()) }
                        dragging = null
                    },
                    colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(formatTime(dragging?.let { (it * duration).toLong() } ?: position), color = Color.White, fontSize = 13.sp)
                    badges.forEach { QualityBadge(it) }
                    Spacer(Modifier.weight(1f))
                    if (hasPrevious) PillButton("⏮ السابقة", small = true, onClick = onPrevious)
                    if (hasNext) PillButton("الحلقة الجاية ⏭", small = true, onClick = onNext)
                    Text(if (duration > 0) "-" + formatTime(duration - position) else "--:--", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MenuPanel(
    menu: Menu,
    tracks: Tracks,
    speed: Float,
    live: Boolean,
    subSize: Int,
    onSubSize: (Int) -> Unit,
    subBackground: Boolean,
    onSubBackground: () -> Unit,
    boost: Boolean,
    onBoost: () -> Unit,
    softwareAudio: Boolean,
    onSoftwareAudio: () -> Unit,
    sleeping: Boolean,
    onSleep: (Int?) -> Unit,
    onClose: () -> Unit,
    onPickTrack: (Tracks.Group, Int) -> Unit,
    onSubtitlesOff: () -> Unit,
    onSpeed: (Float) -> Unit,
) {
    val type = if (menu == Menu.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
    val options = buildList {
        tracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSupported(i)) add(Triple(g, i, g.isTrackSelected(i)))
        }
    }
    val anyText = options.any { it.third }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(menu) { runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(320.dp)
                .background(Color(0xF2141210))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 18.dp),
        ) {
            Text(
                when (menu) {
                    Menu.SUBTITLES -> "الترجمة"
                    Menu.AUDIO -> "الصوت المعلّق"
                    Menu.SPEED -> "سرعة التشغيل"
                    Menu.SLEEP -> "مؤقت النوم"
                    Menu.SOURCES, Menu.INFO, Menu.PICTURE, Menu.QUALITY -> ""
                },
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                when (menu) {
                    Menu.SPEED -> items(Speeds) { s ->
                        MenuRow(
                            if (s == 1f) "عادي" else "×$s",
                            selected = s == speed,
                            modifier = if (s == Speeds.first()) Modifier.focusRequester(firstFocus) else Modifier,
                        ) { onSpeed(s) }
                    }
                    Menu.SUBTITLES -> {
                        item { MenuRow("بدون ترجمة", selected = !anyText, modifier = Modifier.focusRequester(firstFocus), onClick = onSubtitlesOff) }
                        items(options) { (g, i, sel) -> MenuRow(trackName(g, i, "ترجمة"), selected = sel) { onPickTrack(g, i) } }
                        if (options.isEmpty()) item { Hint("هذا الملف ما فيه ترجمات") }
                        item { SectionLabel("حجم الترجمة") }
                        items(SubSizes.size) { n -> MenuRow(SubSizes[n].second, selected = n == subSize) { onSubSize(n) } }
                        item { MenuRow("خلفية داكنة ورا الترجمة", selected = subBackground, onClick = onSubBackground) }
                    }
                    Menu.SLEEP -> {
                        item { MenuRow("بدون", selected = !sleeping, modifier = Modifier.focusRequester(firstFocus)) { onSleep(null) } }
                        items(SleepChoices) { m ->
                            MenuRow(if (m == -1) (if (live) "بعد ساعتين" else "نهاية الحلقة / الفيلم") else "بعد $m دقيقة", selected = false) {
                                onSleep(if (m == -1 && live) 120 else m)
                            }
                        }
                    }
                    Menu.SOURCES, Menu.INFO, Menu.PICTURE, Menu.QUALITY -> Unit
                    Menu.AUDIO -> {
                        items(options.size) { n ->
                            val (g, i, sel) = options[n]
                            MenuRow(
                                trackName(g, i, "صوت"),
                                selected = sel,
                                modifier = if (n == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            ) { onPickTrack(g, i) }
                        }
                        if (options.size <= 1) item { Hint("هذا الملف فيه صوت واحد بس") }
                        item { SectionLabel("خيارات") }
                        item { MenuRow("تعزيز الصوت (للأفلام الهادية)", selected = boost, modifier = if (options.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier, onClick = onBoost) }
                        item { MenuRow("ما فيه صوت؟ استخدم قارئ الصوت الداخلي", selected = softwareAudio, onClick = onSoftwareAudio) }
                    }
                }
            }
        }
    }
}

/**
 * "حجم الصورة": every fit and shape, plus zoom and stretch by steps (buttons work on screens
 * where pinching does not, like many car head units). Changes show at once; the panel stays open.
 */
@Composable
private fun PicturePanel(
    mode: Int,
    zoom: Float,
    stretch: Float,
    onMode: (Int) -> Unit,
    onZoom: (Float) -> Unit,
    onStretch: (Float) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    // Light backdrop: the picture behind stays visible while adjusting.
    Box(Modifier.fillMaxSize().clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(320.dp)
                .background(Color(0xE6141210))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 14.dp),
        ) {
            Text("حجم الصورة", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                item { SectionLabel("التكبير: ${Math.round(zoom * 100)}%") }
                item {
                    StepRow(
                        onMinus = { onZoom(zoom - 0.05f) },
                        onPlus = { onZoom(zoom + 0.05f) },
                        firstFocus = firstFocus,
                    )
                }
                item { SectionLabel("العرض (تمديد أفقي): ${Math.round(stretch * 100)}%") }
                item { StepRow(onMinus = { onStretch(stretch - 0.05f) }, onPlus = { onStretch(stretch + 0.05f) }) }
                item { MenuRow("رجّع كل شي للأصل", selected = false, onClick = onReset) }
                item { SectionLabel("طريقة العرض") }
                items(PictureModes.size) { n ->
                    MenuRow(PictureModes[n].label, selected = n == mode) { onMode(n) }
                }
            }
        }
    }
}

@Composable
private fun StepRow(onMinus: () -> Unit, onPlus: () -> Unit, firstFocus: FocusRequester? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("−  تصغير", Modifier.weight(1f).let { if (firstFocus != null) it.focusRequester(firstFocus) else it }, onClick = onMinus)
        PillButton("+  تكبير", Modifier.weight(1f), onClick = onPlus)
    }
}

/** "⚠️ النت بطيء": load a few minutes ahead and then go on, always do it by itself, or ignore. */
@Composable
private fun SlowNetCard(why: String, auto: Boolean, modifier: Modifier, onPreload: (Int) -> Unit, onAuto: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier
            .widthIn(max = 520.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF2141210))
            .border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("⚠️ النت بطيء والفيديو يقطّع", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(why, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        Text("نوقفه شوي ونحمّل مسبقاً، وبعدها يكمل بدون تقطيع:", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("⏳ دقيقة", Modifier.focusRequester(first), small = true) { onPreload(1) }
            PillButton("⏳ 3 دقايق", small = true) { onPreload(3) }
            PillButton("⏳ 5 دقايق", small = true) { onPreload(5) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (auto) "⚡ تلقائي: شغّال" else "⚡ تلقائي دايماً", small = true, onClick = onAuto)
            PillButton("تجاهل", small = true, onClick = onDismiss)
        }
    }
}

/** "⏳ نجهّز…": how much is ready of what was asked, and "شغّل الحين". */
@Composable
private fun PreloadBox(ready: Long, wanted: Long, modifier: Modifier, onPlayNow: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier
            .width(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("⏳ نحمّل مسبقاً…", color = Color.White, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { (ready.toFloat() / wanted).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Gold,
            trackColor = Color.White.copy(alpha = 0.2f),
        )
        Text("\u2066${formatTime(ready)} / ${formatTime(wanted)}\u2069", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        PillButton("▶ شغّل الحين", Modifier.focusRequester(focus), small = true, onClick = onPlayNow)
    }
}

/**
 * "الجودة": automatic (by the connection's speed) or one of the qualities the stream offers.
 * Most films and episodes from an Xtream account are one file in one quality: then it says so.
 */
@OptIn(UnstableApi::class)
@Composable
private fun QualityPanel(
    autoPreload: Boolean,
    onAutoPreload: () -> Unit,
    onManualPreload: () -> Unit,
    tracks: Tracks,
    auto: Boolean,
    onAuto: () -> Unit,
    onPick: (Tracks.Group, Int, String) -> Unit,
    onClose: () -> Unit,
) {
    val options = buildList {
        tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSupported(i) && g.getTrackFormat(i).height > 0) add(Triple(g, i, g.getTrackFormat(i)))
        }
    }.distinctBy { it.third.height }.sortedByDescending { it.third.height }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    fun label(f: androidx.media3.common.Format): String {
        val h = minOf(f.height, f.width.takeIf { it > 0 } ?: f.height)
        val rate = listOf(f.averageBitrate, f.bitrate, f.peakBitrate).firstOrNull { it > 0 }
        return "${h}p" + (rate?.let { " · " + String.format(Locale.US, "%.1f Mbps", it / 1_000_000f) } ?: "")
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(320.dp)
                .background(Color(0xF2141210))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 18.dp),
        ) {
            Text("الجودة", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                item {
                    MenuRow("⚡ تحميل مسبق تلقائي لما يقطّع", selected = autoPreload, onClick = onAutoPreload)
                }
                item {
                    MenuRow("تحميل مسبق لمرة واحدة", selected = false, onClick = onManualPreload)
                }
                if (options.size > 1) {
                    item { MenuRow("تلقائي (حسب سرعة النت)", selected = auto, modifier = Modifier.focusRequester(firstFocus), onClick = onAuto) }
                    items(options.size) { n ->
                        val (g, i, f) = options[n]
                        MenuRow(label(f), selected = !auto && g.isTrackSelected(i)) { onPick(g, i, "${minOf(f.height, f.width.takeIf { w -> w > 0 } ?: f.height)}p") }
                    }
                } else {
                    val f = options.firstOrNull()?.third
                    item {
                        MenuRow(f?.let { label(it) } ?: "الجودة الأصلية", selected = true, modifier = Modifier.focusRequester(firstFocus), onClick = onClose)
                    }
                    item {
                        Hint(
                            "هذا الفيديو جاي من المزود بجودة وحدة بس، فما يقدر المشغل يختار جودة أقل. " +
                                "للنت البطيء (مثل السيارة): نزّل الحلقات قبل من «📥 تنزيل الحلقات» في صفحة المسلسل، وتشتغل بدون نت.",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
}

/** "معلومات التشغيل": picture, sound and connection details of what is playing. */
@Composable
private fun InfoPanel(info: MediaInfo?, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(340.dp)
                .background(Color(0xF2141210))
                .pointerInput(Unit) { detectTapGestures { } }
                .focusRequester(focus)
                .focusable()
                .padding(vertical = 18.dp),
        ) {
            Text("معلومات التشغيل", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (info == null) {
                Hint("ننتظر يبدأ التشغيل…")
            } else {
                if (info.badges.isNotEmpty()) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        info.badges.forEach { QualityBadge(it) }
                    }
                }
                LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(info.rows) { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(k, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(v, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
private fun MenuRow(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (selected) Gold else Color.White, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = Gold, fontWeight = FontWeight.Bold)
    }
}

@OptIn(UnstableApi::class)
private fun trackName(group: Tracks.Group, index: Int, fallback: String): String {
    val f = group.getTrackFormat(index)
    val language = f.language?.takeIf { it != "und" && it.isNotBlank() }?.let { code ->
        Locale.forLanguageTag(code).getDisplayLanguage(Locale("ar")).takeIf { it.isNotBlank() && it != code } ?: code
    }
    val channels = when (f.channelCount) {
        1 -> "أحادي"
        2 -> "ستيريو"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }.takeIf { group.type == C.TRACK_TYPE_AUDIO }
    return listOfNotNull(f.label?.takeIf { it.isNotBlank() }, language, channels).distinct().joinToString(" · ")
        .ifBlank { "$fallback ${index + 1}" }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (focused) FocusColor.copy(alpha = 0.3f) else Color.Transparent),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    label: String? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (big) 76.dp else 58.dp
    Box(
        modifier
            .size(size)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (focused) FocusColor.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(if (big) 46.dp else 34.dp))
        if (label != null) Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun QualityBadge(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ChannelBanner(number: Int?, title: String, quality: String?, hasPrevious: Boolean, hasNext: Boolean) {
    Row(
        Modifier
            .padding(20.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasPrevious) Text("◀", color = Color.White.copy(alpha = 0.5f))
        number?.let { Text("$it", color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        Column {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            quality?.let { Text(it, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp) }
        }
        if (hasNext) Text("▶", color = Color.White.copy(alpha = 0.5f))
    }
}

@Composable
private fun PillButton(text: String, modifier: Modifier = Modifier, small: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        color = if (focused) Color.Black else Color.White,
        fontSize = if (small) 13.sp else 16.sp,
        fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Gold else Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (small) 10.dp else 18.dp, vertical = if (small) 5.dp else 11.dp),
    )
}

/** "الحلقة الجاية" near the end: counts down, then the next episode starts by itself. */
@Composable
private fun UpNextCard(next: String, seconds: Int, tvHint: Boolean, modifier: Modifier, onPlay: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier
            .width(320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF2141210))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("الحلقة الجاية", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(next, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { (10 - seconds) / 10f },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Gold,
            trackColor = Color.White.copy(alpha = 0.2f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("شغّلها الحين ($seconds)", small = true, onClick = onPlay)
            PillButton("إلغاء", small = true, onClick = onCancel)
        }
        if (tvHint) Text("OK للتشغيل · رجوع للإلغاء", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
    }
}

/** The channel playing now, from every account (same name and quality). */
@Composable
private fun SourcesPanel(title: String, load: suspend () -> List<ChannelSource>, onClose: () -> Unit, onPick: (ChannelSource, List<ChannelSource>) -> Unit) {
    var sources by remember { mutableStateOf<List<ChannelSource>?>(null) }
    LaunchedEffect(Unit) { sources = runCatching { load() }.getOrDefault(emptyList()) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(sources) { runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(360.dp)
                .background(Color(0xF2141210))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 18.dp),
        ) {
            Text("نفس القناة في حساباتك", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text(title, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            val list = sources
            when {
                list == null -> Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Gold, strokeWidth = 2.dp)
                    Text("ندوّر في كل الحسابات…", color = Color.White.copy(alpha = 0.7f))
                }
                list.isEmpty() -> Hint("ما لقينا نفس القناة بنفس الجودة في حساب ثاني")
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(list.size) { n ->
                        val src = list[n]
                        MenuRow(
                            "${src.label}  ·  ${src.account}",
                            selected = false,
                            modifier = if (n == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        ) { onPick(src, list) }
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
}

/** Material icons the app needs beyond the small core set (paths from Material Icons, Apache 2.0). */
object PlayerIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.White))
        .build()

    val Pause = icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val Replay = icon("Replay", "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z")
    val Forward = icon("Forward", "M12 5V1l5 5-5 5V7c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8z")
    val Subtitles = icon("Subtitles", "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z")
    val Audio = icon("Audio", "M12 3v9.28c-.47-.17-.97-.28-1.5-.28C8.01 12 6 14.01 6 16.5S8.01 21 10.5 21c2.31 0 4.2-1.75 4.45-4H15V6h4V3h-7z")
    val Aspect = icon("Aspect", "M19 12h-2v3h-3v2h5v-5zM7 9h3V7H5v5h2V9zm14-6H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16.01H3V4.99h18v14.02z")
    val Speed = icon("Speed", "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z")
    val Brightness = icon("Brightness", "M20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69L23.31 12 20 8.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6zm0-10c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z")
    val Volume = icon("Volume", "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z")
    val Lock = Icons.Filled.Lock
    val Quality = icon("Quality", "M19 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-8 12H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zm7-1c0 .55-.45 1-1 1h-.75v1.5h-1.5V15H14c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v4zm-3.5-.5h2v-3h-2v3z")
    val YouTube = icon("YouTube", "M10 15l5.19-3L10 9v6zm11.56-7.83c.13.47.22 1.1.28 1.9.07.8.1 1.49.1 2.09L22 12c0 2.19-.16 3.8-.44 4.83-.25.9-.83 1.48-1.73 1.73-.47.13-1.33.22-2.65.28-1.3.07-2.49.1-3.59.1L12 19c-4.19 0-6.8-.16-7.83-.44-.9-.25-1.48-.83-1.73-1.73-.13-.47-.22-1.1-.28-1.9-.07-.8-.1-1.49-.1-2.09L2 12c0-2.19.16-3.8.44-4.83.25-.9.83-1.48 1.73-1.73.47-.13 1.33-.22 2.65-.28 1.3-.07 2.49-.1 3.59-.1L12 5c4.19 0 6.8.16 7.83.44.9.25 1.48.83 1.73 1.73z")
    val Info = icon("Info", "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z")
    val Sleep = icon("Sleep", "M12.34 2.02C6.59 1.82 2 6.42 2 12c0 5.52 4.48 10 10 10 3.71 0 6.93-2.02 8.66-5.02-7.51-.25-12.09-8.43-8.32-14.96z")
    val Next = icon("Next", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")
    val Previous = icon("Previous", "M6 6h2v12H6zm3.5 6l8.5 6V6z")
    val Sources = icon("Sources", "M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l6 4.5-6 4.5z")
}
