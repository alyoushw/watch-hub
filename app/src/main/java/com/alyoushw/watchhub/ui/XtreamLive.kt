package com.alyoushw.watchhub.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.XtreamApi
import com.alyoushw.watchhub.data.XtreamCategory
import com.alyoushw.watchhub.data.XtreamException
import com.alyoushw.watchhub.data.XtreamItem
import com.alyoushw.watchhub.data.XtreamKind
import com.alyoushw.watchhub.data.XtreamProgram
import com.alyoushw.watchhub.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RECENT = "__recent"
private const val ALL = "__all"
private const val FAVORITES = "__fav"

/** All live channels of the account, grouped by category, with a now/next guide for the chosen one. */
class LiveViewModel(private val api: XtreamApi) : ViewModel() {
    var categories by mutableStateOf<List<XtreamCategory>>(emptyList())
        private set
    var channels by mutableStateOf<List<XtreamItem>>(emptyList())
        private set
    var counts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var category by mutableStateOf(ALL)
    var selected by mutableStateOf<XtreamItem?>(null)
        private set
    var guide by mutableStateOf<List<XtreamProgram>?>(null)
        private set

    private var guideJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                categories = api.categories(XtreamKind.LIVE)
                val all = api.all(XtreamKind.LIVE)
                channels = all
                counts = all.groupingBy { it.categoryId.orEmpty() }.eachCount()
                if (selected == null) select(all.firstOrNull())
            } catch (e: XtreamException) {
                error = e.message
            }
            loading = false
        }
    }

    fun select(channel: XtreamItem?) {
        if (channel == null || channel.id == selected?.id) return
        selected = channel
        guide = null
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            delay(350) // Moving down the list with the remote should not fire a request per row.
            guide = try {
                api.shortEpg(channel.id)
            } catch (e: XtreamException) {
                emptyList()
            }
        }
    }
}

/** Live channels in three panes: categories, channels, and a preview of the chosen channel. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveChannels(showAccounts: Boolean = true) {
    val container = appContainer()
    val context = LocalContext.current
    val account by container.settings.xtream.collectAsStateWithLifecycle()
    val favorites by container.settings.xtreamFavorites.collectAsStateWithLifecycle()
    val recent by container.settings.xtreamRecent.collectAsStateWithLifecycle()
    val vm: LiveViewModel = viewModel(key = "live:${account.host}:${account.username}") { LiveViewModel(container.xtream) }
    var query by remember { mutableStateOf("") }

    val play: (XtreamItem) -> Unit = { ch ->
        container.settings.addXtreamRecent(ch.id)
        PlayerActivity.start(context, container.xtream.playUrls(ch, account), com.alyoushw.watchhub.data.WatchEntry.live(ch, account))
    }

    when {
        vm.loading && vm.channels.isEmpty() -> {
            CenteredLoading()
            return
        }
        vm.error != null && vm.channels.isEmpty() -> {
            MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::load)
            return
        }
    }

    val byId = remember(vm.channels) { vm.channels.associateBy { it.id } }
    val shown = remember(vm.channels, vm.category, favorites, recent, query) {
        val q = query.trim()
        when {
            q.isNotEmpty() -> vm.channels.filter { it.name.contains(q, ignoreCase = true) }
            vm.category == ALL -> vm.channels
            vm.category == FAVORITES -> vm.channels.filter { it.id in favorites }
            vm.category == RECENT -> recent.mapNotNull { byId[it] }
            else -> vm.channels.filter { it.categoryId == vm.category }
        }
    }
    val listState = remember(vm.category, query.isBlank()) { LazyListState() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showAccounts) AccountSwitcher()
            PillSearch(query, { query = it }, "البحث في القنوات", Modifier.fillMaxWidth().padding(top = 4.dp))

            // Right to left: preview, then channels, then categories on the far side.
            Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (wide) {
                    PreviewPane(
                        channel = vm.selected,
                        categoryName = vm.categories.firstOrNull { it.id == vm.selected?.categoryId }?.name,
                        guide = vm.guide,
                        favorite = vm.selected?.id?.let { it in favorites } == true,
                        onPlay = { vm.selected?.let(play) },
                        onFavorite = { vm.selected?.let { container.settings.toggleXtreamFavorite(it.id) } },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }

                Pane(Modifier.weight(if (wide) 1.25f else 1f).fillMaxHeight()) {
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (vm.category == FAVORITES) "اضغط مطولاً على أي قناة عشان تضيفها للمفضلة" else "ما فيه قنوات هنا",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(state = listState, contentPadding = PaddingValues(6.dp)) {
                            itemsIndexed(shown, key = { _, ch -> ch.id }) { index, ch ->
                                ChannelRow(
                                    number = ch.num ?: (index + 1),
                                    channel = ch,
                                    selected = wide && vm.selected?.id == ch.id,
                                    favorite = ch.id in favorites,
                                    onFocus = { if (wide) vm.select(ch) },
                                    onClick = {
                                        // On a big screen the first tap previews, the second plays.
                                        if (wide && vm.selected?.id != ch.id) vm.select(ch) else play(ch)
                                    },
                                    onLongClick = {
                                        container.settings.toggleXtreamFavorite(ch.id)
                                        val added = ch.id !in favorites
                                        Toast.makeText(context, if (added) "أُضيفت للمفضلة" else "انشالت من المفضلة", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            }
                        }
                    }
                }

                CategoryPane(
                    categories = vm.categories,
                    counts = vm.counts,
                    total = vm.channels.size,
                    favorites = favorites.count { it in byId },
                    recent = recent.count { it in byId },
                    selected = if (query.isBlank()) vm.category else null,
                    onSelect = {
                        query = ""
                        vm.category = it
                    },
                    modifier = Modifier.width(if (wide) 250.dp else 132.dp).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun Pane(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) { content() }
}

@Composable
private fun PillSearch(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(Gold),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CategoryPane(
    categories: List<XtreamCategory>,
    counts: Map<String, Int>,
    total: Int,
    favorites: Int,
    recent: Int,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    var filter by remember { mutableStateOf("") }
    val fixed = listOf(
        Triple(RECENT, "شوهد مؤخراً", recent),
        Triple(ALL, "الكل", total),
        Triple(FAVORITES, "المفضلة", favorites),
    )
    val rest = remember(categories, filter) {
        val q = filter.trim()
        categories.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    }
    Pane(modifier) {
        Column {
            PillSearch(filter, { filter = it }, "البحث في الفئات", Modifier.fillMaxWidth().padding(8.dp))
            LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
                if (filter.isBlank()) {
                    items(fixed, key = { it.first }) { (id, name, count) ->
                        CategoryRow(name, count, selected == id) { onSelect(id) }
                    }
                }
                items(rest, key = { it.id }) { c ->
                    CategoryRow(c.name, counts[c.id] ?: 0, selected == c.id) { onSelect(c.id) }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusFrame(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Gold.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) Gold else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    number: Int,
    channel: XtreamItem,
    selected: Boolean,
    favorite: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusFrame(shape)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            number.toString(),
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Box(
            Modifier.size(width = 54.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E1B18)),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.image != null) {
                AsyncImage(
                    model = channel.image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            } else {
                Text("📺", fontSize = 16.sp)
            }
        }
        Text(
            channel.name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
        if (favorite) Icon(Icons.Filled.Star, contentDescription = "مفضلة", tint = Gold, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PreviewPane(
    channel: XtreamItem?,
    categoryName: String?,
    guide: List<XtreamProgram>?,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier,
) {
    Pane(modifier) {
        if (channel == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("اختر قناة", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            PreviewBody(channel, categoryName, guide, favorite, onPlay, onFavorite)
        }
    }
}

@Composable
private fun PreviewBody(
    channel: XtreamItem,
    categoryName: String?,
    guide: List<XtreamProgram>?,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The channel logo on a warm glow, like a lit screen.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.radialGradient(listOf(Gold.copy(alpha = 0.35f), Color(0xFF120F0C)))),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.image != null) {
                AsyncImage(
                    model = channel.image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                )
            } else {
                Text("📺", fontSize = 48.sp)
            }
            Text(
                "● مباشر",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE5484D))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(channel.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        categoryName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        when {
            guide == null -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            guide.isEmpty() -> Text("ما فيه دليل برامج لهذي القناة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                guide.take(2).forEachIndexed { i, p ->
                    val time = listOfNotNull(p.start?.let { XtreamApi.formatTime(it) }, p.end?.let { XtreamApi.formatTime(it) }).joinToString(" - ")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (i == 0) "الحين" else "بعده",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (i == 0) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (time.isNotEmpty()) Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206)),
                modifier = Modifier.weight(1f).height(48.dp).focusFrame(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("تشغيل", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onFavorite, modifier = Modifier.height(48.dp).focusFrame()) {
                Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null, tint = Gold)
                Spacer(Modifier.width(6.dp))
                Text("المفضلة")
            }
        }
    }
}
