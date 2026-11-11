package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alyoushw.watchhub.data.MediaType
import androidx.compose.foundation.combinedClickable
import com.alyoushw.watchhub.data.StatusCache
import com.alyoushw.watchhub.data.countryFlag
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
                Button(modifier = Modifier.focusFrame(), onClick = onAction) { Text(actionLabel) }
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun PosterCard(item: SearchResult, rank: Int? = null, width: Dp = 120.dp, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        // Long press: share it.
        Modifier.width(width).focusHighlight()
            .remoteLongPress { context.shareTitle(item.displayTitle, item.type, item.id) }
            .combinedClickable(onClick = onClick, onLongClick = { context.shareTitle(item.displayTitle, item.type, item.id) }),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            Poster(item.posterPath, width = width)
            RatingChip(item.voteAverage, Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (item.type == MediaType.TV) {
                StatusBadge(item.id, Modifier.align(Alignment.TopStart).padding(4.dp))
            }
            countryFlag(item.country)?.let { flag ->
                Text(
                    flag,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
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
            // Always two lines high: a row's height must not change while moving along it.
            minLines = 2,
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
                TextButton(modifier = Modifier.focusFrame(), onClick = onSeeAll) { Text("عرض الكل") }
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
/** Yellow outline only (no fill) on a focused button or chip, for the TV remote. */
@Composable
fun Modifier.focusFrame(shape: Shape = RoundedCornerShape(50)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .border(width = if (focused) 3.dp else 0.dp, color = if (focused) FocusColor else Color.Transparent, shape = shape)
}

@Composable
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(10.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "focusScale")
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        // Warm glow behind the focused card, like a lit poster.
        .shadow(
            elevation = if (focused) 18.dp else 0.dp,
            shape = shape,
            ambientColor = Gold,
            spotColor = Gold,
        )
        // Drawn on top, never changing the card's size: a size change on focus made rows shift
        // and the screen shake while moving with the remote.
        .border(width = if (focused) 3.dp else 0.dp, color = if (focused) FocusColor else Color.Transparent, shape = shape)
}

/**
 * A very wide, short screen (car head units like 1920×720): the tabs move to a side rail and
 * big banners get more of the height.
 */
@Composable
fun isWideShort(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return c.screenHeightDp < 560 && c.screenWidthDp >= c.screenHeightDp * 1.9f
}

/** After a crash: what went wrong, so the user can send a photo of it. Shown once. */
@Composable
fun CrashNotice() {
    val context = LocalContext.current
    val file = remember { java.io.File(context.filesDir, com.alyoushw.watchhub.CRASH_FILE) }
    var text by remember { mutableStateOf(runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()) }
    val t = text ?: return
    fun done() {
        runCatching { file.delete() }
        text = null
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = ::done,
        title = { Text("التطبيق طلع بسبب خطأ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("صوّر هالرسالة وأرسلها عشان نصلّح المشكلة:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    t.lines().filter { it.isNotBlank() }.take(14).joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(modifier = Modifier.focusFrame(), onClick = ::done) { Text("تمام") }
        },
    )
}

/**
 * Shares a film or series with the phone's share sheet: its name and its page on the user's
 * Watch Hub site (never a stream link from the Xtream account, which carries the password).
 */
fun android.content.Context.shareTitle(title: String, type: MediaType?, id: Int?) {
    val site = (applicationContext as com.alyoushw.watchhub.WatchHubApp).container.settings.webUrl.value.trimEnd('/')
    val link = when {
        id == null || type == null -> null
        type == MediaType.ANIME -> "https://myanimelist.net/anime/$id"
        else -> "$site/#/title/${type.path}/$id"
    }
    val kind = when (type) {
        MediaType.MOVIE -> "فيلم"
        MediaType.TV -> "مسلسل"
        MediaType.ANIME -> "أنمي"
        null -> "عمل"
    }
    val text = "🎬 أنصحك بـ$kind «$title»" + (link?.let { "\n$it" } ?: "\n$site")
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    try {
        startActivity(android.content.Intent.createChooser(send, "مشاركة «$title»").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(this, "المشاركة غير متوفرة على هذا الجهاز", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Shares a title from the Xtream account: finds its TMDB page by name first. */
suspend fun android.content.Context.shareByName(api: com.alyoushw.watchhub.data.TmdbApi, name: String, type: MediaType, knownId: Int? = null) {
    val clean = com.alyoushw.watchhub.data.XtreamApi.normalizeTitle(name)
    val hit = if (knownId != null) null else runCatching { api.search(clean).firstOrNull { it.type == type } }.getOrNull()
    shareTitle(hit?.displayTitle ?: name, type, knownId ?: hit?.id)
}

/**
 * How lists scroll to the item the remote moves to: only when it is not fully on screen, and
 * only as far as needed (with a little room around it). Compose's TV default keeps the focused
 * item at a fixed point instead, scrolling rows and the page on every press, which made the
 * screen shake on TVs (worse with right-to-left rows).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class CalmBringIntoView(private val margin: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            // Fully visible (with room around it): stay still.
            offset >= margin && end <= containerSize - margin -> 0f
            // Bigger than the space: line up its start.
            size > containerSize - 2 * margin -> offset - margin
            offset < margin -> offset - margin
            else -> end - (containerSize - margin)
        }
    }
}

/**
 * Before sharing a play link from the Xtream account: it starts playing at once in any player
 * (VLC…), but it carries the account's user name and password. Shared only after this warning.
 */
@Composable
fun StreamShareDialog(title: String, url: String, onDone: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        title = { Text("مشاركة رابط التشغيل") },
        text = {
            Text(
                "الرابط يشغّل «$title» على طول في أي مشغل (مثل VLC). بس انتبه: الرابط فيه اسم المستخدم وكلمة المرور حق حسابك، " +
                    "فأي أحد يوصله يقدر يستخدم حسابك، وبعض المزودين يسمحون بجهاز واحد بس في نفس الوقت. شاركه مع ناس تثق فيهم.",
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(modifier = Modifier.focusFrame(), onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                    .putExtra(android.content.Intent.EXTRA_TEXT, "▶ $title\n$url")
                try {
                    context.startActivity(android.content.Intent.createChooser(send, "مشاركة «$title»").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, "المشاركة غير متوفرة على هذا الجهاز", android.widget.Toast.LENGTH_SHORT).show()
                }
                onDone()
            }) { Text("شارك الرابط") }
        },
        dismissButton = { androidx.compose.material3.TextButton(modifier = Modifier.focusFrame(), onClick = onDone) { Text("إلغاء") } },
    )
}

/**
 * Lists and text pages keep a readable width on wide screens (landscape phones, tablets,
 * car screens): centred, no wider than [max], instead of lines running across the whole screen.
 */
fun Modifier.readableWidth(max: androidx.compose.ui.unit.Dp = 820.dp): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)

/**
 * Long press with a TV remote: holding OK (or pressing the remote's Menu key) does what a long
 * touch does — the remote sends a repeating key, which `combinedClickable` does not take as one.
 * Put it before the `clickable` of the same element.
 */
fun Modifier.remoteLongPress(onLongPress: () -> Unit): Modifier = composed {
    var fired by remember { mutableStateOf(false) }
    Modifier.onPreviewKeyEvent { e ->
        val ok = e.key == androidx.compose.ui.input.key.Key.DirectionCenter || e.key == androidx.compose.ui.input.key.Key.Enter ||
            e.key == androidx.compose.ui.input.key.Key.NumPadEnter
        val down = e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
        when {
            e.key == androidx.compose.ui.input.key.Key.Menu && down -> {
                onLongPress()
                true
            }
            ok && down && e.nativeKeyEvent.repeatCount > 0 -> {
                if (!fired) {
                    fired = true
                    onLongPress()
                }
                true
            }
            // The release after a long press does not also count as a click.
            ok && !down && fired -> {
                fired = false
                true
            }
            else -> false
        }
    }
}

/**
 * Text boxes on TV: the remote can pass through them. Up / down go to the next control (the box
 * does not grab them); the keyboard opens only when OK is pressed on the box, not when the remote
 * merely lands on it (see [tvKeyboard]).
 */
fun Modifier.tvTextField(): Modifier = composed {
    if (!isTv()) return@composed Modifier
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Modifier.onPreviewKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            androidx.compose.ui.input.key.Key.DirectionDown -> {
                keyboard?.hide()
                focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                true
            }
            androidx.compose.ui.input.key.Key.DirectionUp -> {
                keyboard?.hide()
                focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                true
            }
            androidx.compose.ui.input.key.Key.DirectionCenter, androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter -> {
                keyboard?.show()
                true
            }
            else -> false
        }
    }
}

/** On TV the keyboard waits for OK on the box (see [tvTextField]); elsewhere as usual. */
@Composable
fun tvKeyboard(base: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default): androidx.compose.foundation.text.KeyboardOptions =
    if (isTv()) base.copy(showKeyboardOnFocus = false) else base
