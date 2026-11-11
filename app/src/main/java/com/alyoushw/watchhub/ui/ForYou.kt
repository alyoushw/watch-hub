package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.Pick
import com.alyoushw.watchhub.data.XtreamKind
import kotlinx.coroutines.delay

/**
 * "✨ مختار لك": suggestions from what the user watches, each with its reason, and — right after
 * finishing something — "خلصت X؟ أنصحك الحين بـ…". [type] keeps the series tab to series and the
 * films tab to films.
 */
@Composable
fun ForYouRow(type: MediaType?, onOpen: (MediaType, Int) -> Unit) {
    val container = appContainer()
    val rec = container.recommender
    val history by container.history.entries.collectAsStateWithLifecycle()
    val data by container.store.data.collectAsStateWithLifecycle()
    val picks by rec.picks.collectAsStateWithLifecycle()
    val finished by rec.finished.collectAsStateWithLifecycle()
    LaunchedEffect(history.size, history.count { it.finished }, data.watchlist.size) {
        delay(800)
        rec.refresh()
    }
    val shown = picks.orEmpty().filter { type == null || type == MediaType.ANIME || it.item.type == type }.take(15)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        finished?.let { f ->
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Gold.copy(alpha = 0.12f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎉 خلصت «${f.title}»؟ أنصحك الحين بـ:", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(modifier = Modifier.focusFrame(), onClick = rec::acknowledgeFinished) { Text("تمام") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(f.picks, key = { "f:${it.item.mediaType}:${it.item.id}" }) { p -> PickCard(p, onOpen, onNope = { rec.notForMe(p) }) }
                }
            }
        }
        if (shown.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SectionTitle("✨ مختار لك")
                Text("مبني على اللي تشوفه، وتحت كل واحد ليش يناسبك", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { "${it.item.mediaType}:${it.item.id}" }) { p -> PickCard(p, onOpen, onNope = { rec.notForMe(p) }) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PickCard(p: Pick, onOpen: (MediaType, Int) -> Unit, onNope: () -> Unit) {
    val container = appContainer()
    val item = p.item
    // "🟢 في حسابك": the title is in one of the user's Xtream accounts.
    var inAccount by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) {
        val accounts = container.settings.xtreamAccounts.value.filter { it.isComplete }
        val kind = if (item.type == MediaType.MOVIE) XtreamKind.MOVIE else XtreamKind.SERIES
        for (acc in accounts) {
            val hit = runCatching { container.xtream.findMatch(kind, item.id, listOf(item.displayTitle), item.year, acc) }.getOrNull()
            if (hit != null) {
                inAccount = acc.label
                break
            }
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.width(150.dp).focusHighlight().remoteLongPress { context.shareTitle(item.displayTitle, item.type, item.id) }.combinedClickable(
            onClick = { item.type?.let { onOpen(it, item.id) } },
            onLongClick = { context.shareTitle(item.displayTitle, item.type, item.id) },
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            Poster(item.posterPath, width = 150.dp)
            inAccount?.let {
                Text(
                    "🟢 في حسابك",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        // Fixed heights: cards of one row stay the same size, so the page does not jump while moving.
        Text(item.displayTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(p.reason, style = MaterialTheme.typography.labelSmall, color = Gold, minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            "مو لي ✕",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.focusFrame(RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).clickable(onClick = onNope).padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}
