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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

private enum class Menu { SUBTITLES, AUDIO, SPEED }

/** Picture modes for the "حجم الصورة" button. */
private val ResizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "ملاءمة الشاشة",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "ملء وقص",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "تمديد",
)

private val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** What a gesture or key is changing right now, shown in the middle of the screen. */
private data class Indicator(val icon: ImageVector, val text: String, val level: Float? = null)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(player: ExoPlayer, title: String, subtitle: String?, live: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val tv = remember { context.isTvDevice }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Menu?>(null) }
    var resize by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var indicator by remember { mutableStateOf<Indicator?>(null) }
    var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var speed by remember { mutableFloatStateOf(1f) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }

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
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            delay(500)
        }
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

    BackHandler(enabled = menu != null || (tv && controls)) {
        if (menu != null) menu = null else controls = false
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
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controls && !locked) {
                            seekBy(if (e.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000)
                            true
                        } else {
                            false
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!controls) {
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
                update = { it.resizeMode = ResizeModes[resize].first },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
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
                                        zoom = (startZoom * d / startDist).coerceIn(1f, 4f)
                                        flash(Indicator(PlayerIcons.Aspect, "تكبير ×" + String.format(Locale.US, "%.1f", zoom)))
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (pressed.size == 1 && mode != 4) {
                                    val c = pressed[0]
                                    val dx = c.position.x - down.position.x
                                    val dy = c.position.y - down.position.y
                                    if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                                        mode = when {
                                            abs(dx) > abs(dy) -> if (live) -1 else 1
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
                                    }
                                    if (mode > 0) c.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            if (mode == 1) player.seekTo(target)
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
                        playing = playing,
                        position = seekPreview ?: position,
                        duration = duration,
                        speed = speed,
                        resizeLabel = ResizeModes[resize].second,
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
                            resize = (resize + 1) % ResizeModes.size
                            zoom = 1f
                            flash(Indicator(PlayerIcons.Aspect, ResizeModes[resize].second))
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

            menu?.let { m ->
                MenuPanel(
                    menu = m,
                    tracks = tracks,
                    speed = speed,
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
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            ToolButton(PlayerIcons.Subtitles, "الترجمة") { onMenu(Menu.SUBTITLES) }
            ToolButton(PlayerIcons.Audio, "الصوت المعلّق") { onMenu(Menu.AUDIO) }
            ToolButton(PlayerIcons.Aspect, resizeLabel, onResize)
            if (!live) ToolButton(PlayerIcons.Speed, if (speed == 1f) "السرعة" else "×$speed") { onMenu(Menu.SPEED) }
            ToolButton(PlayerIcons.Lock, "قفل الشاشة", onLock)
        }

        // Middle: skip back, play/pause, skip forward.
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            if (!live) RoundButton(PlayerIcons.Replay, "رجوع 10 ثواني", label = "10") { onSeekBy(-10_000) }
            RoundButton(
                if (playing) PlayerIcons.Pause else Icons.Filled.PlayArrow,
                if (playing) "إيقاف مؤقت" else "تشغيل",
                Modifier.focusRequester(playFocus),
                big = true,
                onClick = onPlayPause,
            )
            if (!live) RoundButton(PlayerIcons.Forward, "تقديم 10 ثواني", label = "10") { onSeekBy(10_000) }
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
                Text(
                    "● مباشر",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE5484D)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
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
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(dragging?.let { (it * duration).toLong() } ?: position), color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
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
                    }
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
}
