package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.StatusCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.alyoushw.watchhub.data.SearchResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.StreamingService
import com.alyoushw.watchhub.data.TmdbApi
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Poster(path: String?, width: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .width(width)
            .height(width * 1.5f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            AsyncImage(
                model = TmdbApi.image(path, "w342"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("🎬", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun ServiceBadge(service: StreamingService, modifier: Modifier = Modifier) {
    Text(
        text = service.label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(service.color))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun ServiceDot(color: Color, letter: String, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun MessageBox(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

@Composable
fun BadgeRow(services: List<StreamingService>) {
    if (services.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        services.forEach { ServiceBadge(it) }
    }
}

fun formatMoney(value: Double, currency: String = "SAR"): String {
    val number = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.2f", value)
    val unit = if (currency == "SAR") "ر.س" else currency
    return "$number $unit"
}

val ArabicDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ar"))

fun daysLabel(days: Long): String = when {
    days <= 0L -> "اليوم"
    days == 1L -> "بكرة"
    days == 2L -> "بعد يومين"
    days in 3L..10L -> "بعد $days أيام"
    else -> "بعد $days يوم"
}

fun ratingLabel(value: Double?): String? =
    value?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) }

@Composable
fun RatingChip(value: Double?, modifier: Modifier = Modifier) {
    val label = ratingLabel(value) ?: return
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Poster with title; [rank] draws a large "top 10" number on it. */
@Composable
fun PosterCard(item: SearchResult, rank: Int? = null, width: Dp = 120.dp, onClick: () -> Unit) {
    Column(
        Modifier.width(width).focusHighlight().clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            Poster(item.posterPath, width = width)
            RatingChip(item.voteAverage, Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (item.type == MediaType.TV) {
                StatusBadge(item.id, Modifier.align(Alignment.TopStart).padding(4.dp))
            }
            if (rank != null) {
                Text(
                    "$rank",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 40.sp,
                    style = MaterialTheme.typography.displaySmall.copy(
                        shadow = Shadow(Color.Black, Offset(2f, 2f), 8f),
                    ),
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp),
                )
            }
        }
        Text(
            item.displayTitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Wide card with the backdrop image and the title over it. */
@Composable
fun BackdropCard(item: SearchResult, width: Dp = 280.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = TmdbApi.image(item.backdropPath ?: item.posterPath, "w780"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                item.displayTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ratingLabel(item.voteAverage)?.let {
                Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Horizontal row of titles with a heading. */
@Composable
fun TitleRow(
    title: String,
    items: List<SearchResult>,
    subtitle: String? = null,
    service: StreamingService? = null,
    ranked: Boolean = false,
    wide: Boolean = false,
    onSeeAll: (() -> Unit)? = null,
    onOpen: (SearchResult) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (service != null) ServiceDot(Color(service.color), service.label.take(1), size = 24.dp)
            Column(Modifier.weight(1f)) {
                SectionTitle(title)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll) { Text("عرض الكل") }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(items, key = { _, it -> "${it.mediaType}:${it.id}" }) { index, item ->
                if (wide) {
                    BackdropCard(item) { onOpen(item) }
                } else {
                    PosterCard(item, rank = if (ranked) index + 1 else null) { onOpen(item) }
                }
            }
        }
    }
}

@Composable
fun AppIcon(packageName: String, size: Dp = 32.dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** "مستمر / انتهى / انلغى / قريباً" on a series poster; looked up once and cached. */
@Composable
fun StatusBadge(tvId: Int, modifier: Modifier = Modifier) {
    val cache = appContainer().statusCache
    var status by remember(tvId) { mutableStateOf(cache.cached(tvId)) }
    LaunchedEffect(tvId) {
        if (status == null) status = cache.status(tvId)
    }
    val (label, color) = StatusCache.label(status) ?: return
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(color).copy(alpha = 0.92f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** True on Android TV / Google TV / Fire TV (driven by a remote, not touch). */
@Composable
fun isTv(): Boolean {
    val context = LocalContext.current
    return remember {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }
}

/**
 * Makes the remote's current position obvious on TV: a bright border and a slight zoom
 * on the focused card. Put it before `clickable` so it sees that element's focus.
 */
@Composable
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(10.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "focusScale")
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = if (focused) MaterialTheme.colorScheme.secondary else Color.Transparent,
            shape = shape,
        )
}
