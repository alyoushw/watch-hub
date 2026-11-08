package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.SearchResult
import com.alyoushw.watchhub.data.TmdbApi
import com.alyoushw.watchhub.data.WatchItem
import com.alyoushw.watchhub.data.countryFlag
import com.alyoushw.watchhub.data.countryLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tabs drawn over the hero: text with a gold underline on the selected one. */
@Composable
fun HeroTabs(
    selected: MediaType,
    onSelect: (MediaType) -> Unit,
    onRefresh: () -> Unit,
    live: Boolean = false,
    onLive: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Watch Hub", color = Gold, fontWeight = FontWeight.Black, fontSize = 22.sp)
        // "مباشر" (the Xtream Codes live channels) comes first, before series and films.
        val entries = buildList<Pair<MediaType?, String>> {
            if (onLive != null) add(null to "مباشر")
            add(MediaType.TV to "مسلسلات")
            add(MediaType.MOVIE to "أفلام")
            add(MediaType.ANIME to "أنمي")
        }
        entries.forEach { (type, label) ->
            val on = if (type == null) live else !live && type == selected
            Column(
                Modifier.focusFrame(RoundedCornerShape(8.dp))
                    .clickable { if (type == null) onLive?.invoke() else onSelect(type) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    color = if (on) Color.White else Color.White.copy(alpha = 0.65f),
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 17.sp,
                )
                Box(
                    Modifier.padding(top = 4.dp).width(if (on) 22.dp else 0.dp).height(3.dp)
                        .clip(RoundedCornerShape(2.dp)).background(Gold),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(modifier = Modifier.focusFrame(), onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "تحديث", tint = Color.White)
        }
    }
}

/**
 * Big rotating banner of trending titles: full-width backdrop, dark gradient, large title,
 * a short story line, "شاهد الحين" and "+" (add to my list).
 */
@Composable
fun HeroCarousel(
    items: List<SearchResult>,
    header: @Composable () -> Unit,
    onOpen: (SearchResult) -> Unit,
) {
    val container = appContainer()
    val data by container.store.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val slides = items.filter { it.backdropPath != null || it.posterPath != null }.take(8)
    val pager = rememberPagerState { slides.size }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val tv = isTv()
    val height = if (tv) screenHeight * 0.66f else (screenHeight * 0.58f).coerceAtMost(520.dp)

    // Advance by itself every 7 seconds.
    LaunchedEffect(slides.size) {
        if (slides.size < 2) return@LaunchedEffect
        while (true) {
            delay(7000)
            pager.animateScrollToPage((pager.currentPage + 1) % slides.size)
        }
    }

    Box(Modifier.fillMaxWidth().height(height)) {
        if (slides.isNotEmpty()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val item = slides[page]
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = TmdbApi.image(item.backdropPath ?: item.posterPath, "w1280"),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Darken towards the bottom and the text side so the title stays readable.
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.55f),
                                0.35f to Color.Transparent,
                                0.6f to Color(0xCC0E0D0B),
                                1f to MaterialTheme.colorScheme.background,
                            ),
                        ),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f),
                            ),
                        ),
                    )
                    HeroText(
                        item = item,
                        saved = data.watchlist.any { w -> item.type?.let { WatchItem.keyOf(it, item.id) } == w.key },
                        onWatch = { onOpen(item) },
                        onAdd = {
                            val type = item.type ?: return@HeroText
                            scope.launch {
                                container.store.upsertWatch(
                                    WatchItem(id = item.id, type = type, title = item.displayTitle, posterPath = item.posterPath),
                                )
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                    )
                }
            }

            // Arrows and dots.
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedIconButton(
                    modifier = Modifier.size(38.dp).focusFrame(CircleShape),
                    onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1 + slides.size) % slides.size) } },
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "السابق", tint = Gold) }
                OutlinedIconButton(
                    modifier = Modifier.size(38.dp).focusFrame(CircleShape),
                    onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % slides.size) } },
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "التالي", tint = Gold) }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(slides.size) { i ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier.height(5.dp).width(if (on) 18.dp else 5.dp).clip(CircleShape)
                            .background(if (on) Gold else Color.White.copy(alpha = 0.4f)),
                    )
                }
            }
        }
        header()
    }
}

@Composable
private fun HeroText(
    item: SearchResult,
    saved: Boolean,
    onWatch: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(0.82f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "🔥 رائج",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Text(
            item.displayTitle,
            color = Color.White,
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            item.type?.label,
            item.country?.let { c -> countryLabel(c)?.let { "${countryFlag(c).orEmpty()} $it".trim() } },
            item.year,
            ratingLabel(item.voteAverage),
        ).joinToString("  ·  ")
        Text(meta, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
        if (!item.overview.isNullOrBlank()) {
            Text(
                item.overview,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onWatch,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(48.dp).focusFrame(RoundedCornerShape(12.dp)),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("  شاهد الحين", fontWeight = FontWeight.Bold)
            }
            OutlinedIconButton(
                onClick = onAdd,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(48.dp).focusFrame(RoundedCornerShape(12.dp)),
            ) {
                Icon(
                    if (saved) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = if (saved) "في قائمتي" else "أضفه لقائمتي",
                    tint = Color.White,
                )
            }
        }
    }
}
