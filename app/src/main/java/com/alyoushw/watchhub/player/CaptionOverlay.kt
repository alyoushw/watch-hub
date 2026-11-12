package com.alyoushw.watchhub.player

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.alyoushw.watchhub.data.CaptionTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.TreeMap

/** Uses the existing player's decoded cues, including embedded and attached text tracks. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun CaptionOverlay(
    player: Player, arabic: Boolean, delayMs: Long, language: String?, textAvailable: Boolean,
    size: Float, color: Int, background: Boolean, modifier: Modifier = Modifier,
    onStatus: (String?) -> Unit,
) {
    if (!arabic && delayMs == 0L) return
    val status by rememberUpdatedState(onStatus)
    val translator = remember { CaptionTranslator() }
    val timeline = remember(player) { TreeMap<Long, List<Cue>>() }
    var revision by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var rendered by remember { mutableStateOf<List<Cue>>(emptyList()) }
    DisposableEffect(player) {
        timeline[player.currentPosition] = player.currentCues.cues.toList()
        revision++
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                timeline[player.currentPosition] = cueGroup.cues.toList()
                while (timeline.size > 1200) timeline.pollFirstEntry()
                revision++
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                timeline.clear()
                revision++
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); translator.close() }
    }
    LaunchedEffect(player) {
        while (true) { position = player.currentPosition; delay(50) }
    }
    val cues by remember(timeline, delayMs) { derivedStateOf {
        revision
        timeline.floorEntry(position - delayMs)?.value.orEmpty()
    } }
    LaunchedEffect(cues, arabic, language, textAvailable) {
        rendered = cues
        if (!arabic) { status(null); return@LaunchedEffect }
        if (!textAvailable) { status("اختر مسار ترجمة نصية أو أضف ملف SRT / WebVTT"); return@LaunchedEffect }
        if (cues.isEmpty()) return@LaunchedEffect
        if (cues.any { it.text == null }) { status("المسار مصوّر؛ الترجمة إلى العربية تحتاج نصاً"); return@LaunchedEffect }
        try {
            val translated = cues.map { cue ->
                val text = translator.translate(cue.text.toString(), language) { status("جارٍ تجهيز نموذج الترجمة؛ قد يحتاج تنزيله أول مرة") }
                cue.buildUpon().setText(text).build()
            }
            rendered = translated
            status("العربية · ترجمة النصوص على الجهاز")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status("تعذّرت الترجمة إلى العربية؛ يُعرض النص الأصلي") }
    }
    AndroidView(factory = { SubtitleView(it) }, update = { view ->
        view.setApplyEmbeddedStyles(false)
        view.setFractionalTextSize(size)
        view.setStyle(CaptionStyleCompat(color, if (background) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
        view.setCues(rendered)
        view.contentDescription = rendered.mapNotNull { it.text?.toString() }.joinToString("\n")
    }, modifier = modifier)
}
