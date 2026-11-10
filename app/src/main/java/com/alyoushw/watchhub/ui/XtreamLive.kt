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
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.data.XtreamAccount
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.QueueItem
import com.alyoushw.watchhub.player.channelKey
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RECENT = "__recent"
private const val ALL = "__all"
private const val FAVORITES = "__fav"
private const val FOLDER = "__folder:"

/**
 * Folder names the app suggests for a channel, from its name and category: a sports channel
 * offers "رياضة", a news channel "أخبار", and so on.
 */
fun suggestFolders(channelName: String, categoryName: String?): List<String> {
    val t = " ${channelName.lowercase()} ${categoryName.orEmpty().lowercase()} "
    return FolderHints.filter { (_, words) -> words.any { it in t } }.map { it.first }
}

private val FolderHints = listOf(
    "رياضة" to listOf("sport", "bein", "ssc", "dazn", "espn", "kass", "football", "كورة", "كوره", "رياض", "nba", "ufc", "wwe", " f1", "arryadia", "eurosport", "premier league", "laliga", "on time"),
    "أخبار" to listOf("news", "اخبار", "أخبار", "jazeera", "الجزيرة", "العربية", "alarabiya", "cnn", "bbc", "france 24", "الحدث", "alhadath", "sky news", "اخباري"),
    "أطفال" to listOf("kids", "cartoon", "spacetoon", "سبيستون", "mbc 3", "mbc3", "baraem", "براعم", "jeem", "جيم", "disney", "nick", "majid", "ماجد", "طيور", "toyor", "اطفال", "أطفال"),
    "أفلام" to listOf("movie", "cinema", "film", "افلام", "أفلام", "سينما", "mbc 2", "mbc2", "mbc max", "rotana cinema", "action", "box office"),
    "مسلسلات" to listOf("drama", "دراما", "series", "مسلسل", "mbc 4", "mbc4", "mbc drama", "rotana drama", "shahid", "شاهد"),
    "دينية" to listOf("quran", "قرآن", "قران", "sunnah", "سنة", "iqraa", "اقرأ", "majd", "المجد", "makkah", "مكة", "madinah", "المدينة", "islam", "اسلام"),
    "موسيقى" to listOf("music", "موسيقى", "اغاني", "أغاني", "clip", "كليب", "mazzika", "مزيكا", "mtv", "wanasah", "وناسة"),
    "وثائقي" to listOf("documentary", "وثائقي", "nat geo", "national geographic", "discovery", "animal planet", "history", "وثائقية"),
)

/** A channel and the account it comes from (search runs over every account). */
data class LiveHit(val channel: XtreamItem, val account: XtreamAccount) {
    val key get() = "${account.id}:${channel.id}"
}

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
    var selected by mutableStateOf<LiveHit?>(null)
        private set
    var guide by mutableStateOf<List<XtreamProgram>?>(null)
        private set

    /** Search results (null while there is no search, or it is still running). */
    var results by mutableStateOf<List<LiveHit>?>(null)
        private set

    /** A channel ready to search: its lower-case name and its name without prefixes or quality words. */
    private class Indexed(val hit: LiveHit, val name: String, val key: String)

    private var pool: List<Indexed> = emptyList()
    private var poolFor: List<String> = emptyList()
    private var searchJob: Job? = null

    /**
     * Searches the channels of every account ([accounts]; the active one first). Runs off the
     * main thread a moment after typing stops, so a TV with tens of thousands of channels stays smooth.
     */
    fun search(query: String, accounts: List<XtreamAccount>, active: XtreamAccount) {
        searchJob?.cancel()
        val q = query.trim()
        results = null
        if (q.isEmpty()) return
        searchJob = viewModelScope.launch {
            delay(300)
            val all = indexFor(if (accounts.size > 1) accounts else listOf(active))
            val lower = q.lowercase()
            val key = channelKey(q)
            results = withContext(Dispatchers.Default) {
                all.asSequence()
                    .filter { it.name.contains(lower) || (key.isNotEmpty() && it.key.contains(key)) }
                    .map { it.hit }
                    .distinctBy { it.key }
                    .sortedBy { if (it.account.id == active.id) 0 else 1 }
                    .take(500)
                    .toList()
            }
        }
    }

    private suspend fun indexFor(accounts: List<XtreamAccount>): List<Indexed> {
        val ids = accounts.map { it.id }
        if (ids == poolFor && pool.isNotEmpty()) return pool
        val hits = coroutineScope {
            accounts.map { acc ->
                async {
                    try {
                        api.all(XtreamKind.LIVE, acc).map { LiveHit(it, acc) }
                    } catch (e: XtreamException) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        pool = withContext(Dispatchers.Default) { hits.map { Indexed(it, it.channel.name.lowercase(), channelKey(it.channel.name)) } }
        poolFor = ids
        return pool
    }

    private var guideJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                categories = api.categories(XtreamKind.LIVE).distinctBy { it.id }
                val all = api.all(XtreamKind.LIVE)
                channels = all
                counts = all.groupingBy { it.categoryId.orEmpty() }.eachCount()
            } catch (e: XtreamException) {
                error = e.message
            }
            loading = false
        }
    }

    fun select(hit: LiveHit?) {
        if (hit == null || hit.key == selected?.key) return
        selected = hit
        guide = null
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            delay(350) // Moving down the list with the remote should not fire a request per row.
            guide = try {
                api.shortEpg(hit.channel.id, hit.account)
            } catch (e: XtreamException) {
                emptyList()
            }
        }
    }
}

/**
 * Live channels: categories, channels, and a small live picture of the chosen channel.
 * First tap plays a channel in the small picture; the second (or tapping the picture) opens it
 * full screen, where left/right go through the same list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveChannels(showAccounts: Boolean = true) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by container.settings.xtream.collectAsStateWithLifecycle()
    val accounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    val favorites by container.settings.xtreamFavorites.collectAsStateWithLifecycle()
    val folders by container.settings.xtreamFolders.collectAsStateWithLifecycle()
    val historyEntries by container.history.entries.collectAsStateWithLifecycle()
    // "سجل المشاهدة": channels of this account played lately (synced with the account, newest first).
    val recent = remember(historyEntries, account) {
        historyEntries.filter { it.kind == WatchEntry.LIVE && it.accountId == account.id }
            .sortedByDescending { it.updatedAt }.map { it.itemId }.distinct()
    }
    var folderFor by remember { mutableStateOf<LiveHit?>(null) }
    var showGuide by remember { mutableStateOf(false) }
    val vm: LiveViewModel = viewModel(key = "live:${account.host}:${account.username}") { LiveViewModel(container.xtream) }
    var query by remember { mutableStateOf("") }
    /** The channel playing in the small picture, if any. */
    var previewing by remember { mutableStateOf<LiveHit?>(null) }

    // Leaving the app (or opening the full player) stops the small picture.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) previewing = null }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val complete = remember(accounts) { accounts.filter { it.isComplete } }
    val searching = query.isNotBlank()
    LaunchedEffect(query, complete, account) { vm.search(query, complete, account) }

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
    val shown: List<LiveHit> = remember(vm.channels, vm.category, favorites, recent, folders, searching, vm.results, account) {
        fun mine(list: List<XtreamItem>) = list.map { LiveHit(it, account) }
        when {
            searching -> vm.results.orEmpty()
            vm.category.startsWith(FOLDER) -> mine(folders[vm.category.removePrefix(FOLDER)].orEmpty().mapNotNull { byId[it] })
            vm.category == ALL -> mine(vm.channels)
            vm.category == FAVORITES -> mine(vm.channels.filter { it.id in favorites })
            vm.category == RECENT -> mine(recent.mapNotNull { byId[it] })
            else -> mine(vm.channels.filter { it.categoryId == vm.category })
        }
    }
    val listState = remember(vm.category, query.isBlank()) { LazyListState() }
    val multi = complete.size > 1

    fun openFull(hit: LiveHit) {
        container.settings.addXtreamRecent(hit.channel.id)
        val list = shown.ifEmpty { listOf(hit) }
        val items = list.mapIndexed { i, h ->
            QueueItem(container.xtream.playUrls(h.channel, h.account), WatchEntry.live(h.channel, h.account), h.channel.num ?: (i + 1), h.channel)
        }
        val index = list.indexOfFirst { it.key == hit.key }.coerceAtLeast(0)
        // One connection at a time: the small picture lets go before the full player starts.
        val wasPreviewing = previewing != null
        previewing = null
        scope.launch {
            if (wasPreviewing) delay(400)
            PlayerActivity.start(context, items, index)
        }
    }

    fun tap(hit: LiveHit) {
        if (previewing?.key == hit.key) {
            openFull(hit)
        } else {
            vm.select(hit)
            previewing = hit
        }
    }

    if (showGuide) {
        val hit = previewing ?: vm.selected
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text(hit?.channel?.name ?: "دليل البرامج") },
            text = {
                val g = vm.guide.takeIf { vm.selected?.key == hit?.key }
                when {
                    hit == null || g == null -> Text("…")
                    g.isEmpty() -> Text("ما فيه دليل برامج لهذي القناة")
                    else -> GuideList(hit, g.take(6))
                }
            },
            confirmButton = { TextButton(onClick = { showGuide = false }) { Text("تم") } },
        )
    }

    folderFor?.let { hit ->
        FolderDialog(
            hit = hit,
            categoryName = vm.categories.firstOrNull { it.id == hit.channel.categoryId }?.name,
            favorite = hit.channel.id in favorites,
            folders = folders,
            onFavorite = { container.settings.toggleXtreamFavorite(hit.channel.id) },
            onFolder = { name, inside -> container.settings.setInFolder(name, hit.channel.id, inside) },
            onClose = { folderFor = null },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val channelList: @Composable (Modifier) -> Unit = { mod ->
            Pane(mod) {
                when {
                    searching && vm.results == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (multi) "ندوّر في كل حساباتك…" else "ندوّر…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                searching -> "ما لقينا قناة بهالاسم"
                                vm.category == FAVORITES || vm.category.startsWith(FOLDER) -> "اضغط مطولاً على أي قناة عشان تضيفها هنا"
                                vm.category == RECENT -> "القنوات اللي تشوفها بتطلع هنا"
                                else -> "ما فيه قنوات هنا"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(state = listState, contentPadding = PaddingValues(6.dp)) {
                        itemsIndexed(shown, key = { _, h -> h.key }) { index, h ->
                            ChannelRow(
                                number = h.channel.num ?: (index + 1),
                                channel = h.channel,
                                accountLabel = if (multi && searching) h.account.label else null,
                                selected = vm.selected?.key == h.key,
                                playing = previewing?.key == h.key,
                                favorite = h.account.id == account.id && (h.channel.id in favorites || folders.values.any { h.channel.id in it }),
                                onFocus = { if (wide && previewing == null) vm.select(h) },
                                onClick = { tap(h) },
                                onLongClick = {
                                    if (h.account.id != account.id) {
                                        Toast.makeText(context, "بدّل لحساب «${h.account.label}» عشان تضيفها لمجلداته", Toast.LENGTH_SHORT).show()
                                        return@ChannelRow
                                    }
                                    folderFor = h
                                },
                            )
                        }
                    }
                }
            }
        }
        val search: @Composable (Modifier) -> Unit = { mod ->
            PillSearch(query, { query = it }, if (multi) "ابحث عن قناة في كل الحسابات" else "البحث في القنوات", mod)
        }

        if (wide) {
            // Big screens: categories | channels | live picture and guide, edge to edge.
            Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val hit = previewing ?: vm.selected
                    LivePicture(
                        hit = previewing,
                        idle = hit,
                        onOpen = { (previewing ?: vm.selected)?.let(::openFull) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                    Pane(Modifier.fillMaxWidth().weight(1f)) {
                        if (hit == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("اختر قناة: ضغطة تشغّلها هنا، والثانية تفتحها كاملة", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            ChannelInfo(
                                hit = hit,
                                accountLabel = if (multi) hit.account.label else null,
                                categoryName = vm.categories.firstOrNull { it.id == hit.channel.categoryId }?.name.takeIf { hit.account.id == account.id },
                                guide = vm.guide.takeIf { vm.selected?.key == hit.key },
                                favorite = hit.account.id == account.id && hit.channel.id in favorites,
                                onPlay = { openFull(hit) },
                                onFavorite = { if (hit.account.id == account.id) container.settings.toggleXtreamFavorite(hit.channel.id) },
                            )
                        }
                    }
                }
                Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    search(Modifier.fillMaxWidth())
                    if (showAccounts) AccountSwitcher()
                    channelList(Modifier.fillMaxWidth().weight(1f))
                }
                CategoryPane(
                    categories = vm.categories,
                    counts = vm.counts,
                    total = vm.channels.size,
                    favorites = favorites.count { it in byId },
                    recent = recent.count { it in byId },
                    folders = folders.map { (name, ids) -> name to ids.count { it in byId } },
                    selected = if (query.isBlank()) vm.category else null,
                    onSelect = {
                        query = ""
                        vm.category = it
                    },
                    modifier = Modifier.width(230.dp).fillMaxHeight(),
                )
            }
        } else {
            // Phones: the live picture on top while a channel plays, categories as chips, the list below.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                previewing?.let { hit ->
                    Column {
                        LivePicture(hit = hit, idle = hit, onOpen = { openFull(hit) }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), rounded = false)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(hit.channel.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val now = vm.guide?.firstOrNull()?.title.takeIf { vm.selected?.key == hit.key }
                                Text(
                                    listOfNotNull(now?.let { "الحين: $it" }, if (multi) hit.account.label else null).joinToString(" · ").ifEmpty { "اضغط مرة ثانية عشان تفتحها كاملة" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                onClick = { openFull(hit) },
                                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206)),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) { Text("ملء الشاشة", fontWeight = FontWeight.Bold) }
                            TextButton(onClick = { showGuide = true }) { Text("📅") }
                            TextButton(onClick = { previewing = null }) { Text("إيقاف") }
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showAccounts) AccountSwitcher()
                    search(Modifier.fillMaxWidth())
                    if (!searching) {
                        CategoryChips(
                            categories = vm.categories,
                            favorites = favorites.count { it in byId },
                            recent = recent.count { it in byId },
                            folders = folders.keys.toList(),
                            selected = vm.category,
                            onSelect = { vm.category = it },
                        )
                    }
                    channelList(Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp))
                }
            }
        }
    }
}

/**
 * Long press on a channel: which lists it is in. "المفضلة", the user's folders, folders the app
 * suggests from the channel ("رياضة" for beIN…), or a new one.
 */
@Composable
private fun FolderDialog(
    hit: LiveHit,
    categoryName: String?,
    favorite: Boolean,
    folders: Map<String, List<String>>,
    onFavorite: () -> Unit,
    onFolder: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val id = hit.channel.id
    val suggested = remember(hit.key, folders.keys) { suggestFolders(hit.channel.name, categoryName).filter { it !in folders } }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(hit.channel.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FolderRow("⭐ المفضلة", favorite, onFavorite) }
                items(folders.keys.toList(), key = { it }) { name ->
                    val inside = id in folders[name].orEmpty()
                    FolderRow("📁 $name", inside) { onFolder(name, !inside) }
                }
                if (suggested.isNotEmpty()) {
                    item { Text("مقترح لهالقناة", style = MaterialTheme.typography.labelMedium, color = Gold, modifier = Modifier.padding(top = 6.dp)) }
                    items(suggested, key = { "s:$it" }) { name ->
                        FolderRow("＋ مجلد «$name»", false) { onFolder(name, true) }
                    }
                }
                item {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillSearch(newName, { newName = it }, "اسم مجلد جديد", Modifier.weight(1f))
                        TextButton(
                            modifier = Modifier.focusFrame(),
                            enabled = newName.isNotBlank(),
                            onClick = {
                                onFolder(newName.trim().take(30), true)
                                newName = ""
                            },
                        ) { Text("أضف") }
                    }
                }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.focusFrame(), onClick = onClose) { Text("تم") } },
    )
}

@Composable
private fun FolderRow(text: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusFrame(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) Gold.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), color = if (checked) Gold else MaterialTheme.colorScheme.onSurface, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
        if (checked) Text("✓", color = Gold, fontWeight = FontWeight.Bold)
    }
}

/**
 * Now / next on a channel, with 🔔 on what is still to come: a notification shortly before it
 * starts, which opens the channel.
 */
@Composable
private fun GuideList(hit: LiveHit, guide: List<XtreamProgram>) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        guide.forEachIndexed { i, p ->
            val time = listOfNotNull(p.start?.let { XtreamApi.formatTime(it) }, p.end?.let { XtreamApi.formatTime(it) }).joinToString(" - ")
            val start = p.start?.time
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (i == 0) "الحين" else "بعده",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == 0) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
                Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (time.isNotEmpty()) Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (start != null && start > System.currentTimeMillis()) {
                    val on = remember(version) { com.alyoushw.watchhub.data.Reminders.isSet(context, hit.account.id, hit.channel.id, start) }
                    Text(
                        if (on) "🔔" else "🔕",
                        modifier = Modifier.focusFrame(RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).clickable {
                            val set = com.alyoushw.watchhub.data.Reminders.toggle(context, hit.account.id, hit.channel.id, hit.channel.name, p.title, start)
                            version++
                            Toast.makeText(context, if (set) "بنذكرك قبل «${p.title}» بـ 5 دقايق" else "انلغى التذكير", Toast.LENGTH_SHORT).show()
                        }.padding(4.dp),
                    )
                }
            }
        }
    }
}

/** Categories as one scrolling row of chips (phones). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChips(categories: List<XtreamCategory>, favorites: Int, recent: Int, folders: List<String>, selected: String, onSelect: (String) -> Unit) {
    val fixed = buildList {
        add(ALL to "الكل")
        add(RECENT to "🕘 سجل المشاهدة")
        if (favorites > 0) add(FAVORITES to "⭐ المفضلة")
        folders.forEach { add(FOLDER + it to "📁 $it") }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(fixed, key = { it.first }) { (id, name) ->
            FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(name, maxLines = 1) }, modifier = Modifier.focusFrame())
        }
        items(categories, key = { it.id }) { c ->
            FilterChip(selected = selected == c.id, onClick = { onSelect(c.id) }, label = { Text(c.name, maxLines = 1) }, modifier = Modifier.focusFrame())
        }
    }
}

/**
 * The small live picture. [hit] plays (null: shows [idle]'s logo). Tapping it opens the
 * channel full screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LivePicture(hit: LiveHit?, idle: LiveHit?, onOpen: () -> Unit, modifier: Modifier, rounded: Boolean = true) {
    val context = LocalContext.current
    val container = appContainer()
    val player = remember {
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.alyoushw.watchhub.data.Net.streams)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
    }
    DisposableEffect(player) { onDispose { player.release() } }
    var buffering by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf<String?>(null) }

    val urls = remember(hit?.key) { hit?.let { container.xtream.playUrls(it.channel, it.account) }.orEmpty() }
    /** Links that failed for this channel; the next one is tried. */
    var errors by remember { mutableIntStateOf(0) }

    fun start(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                errors++
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                quality = videoSize.height.takeIf { it > 0 }?.let { h -> if (h >= 2100) "4K" else "${minOf(h, videoSize.width)}p" }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(hit?.key) {
        errors = 0
        failed = false
        quality = null
        if (urls.isEmpty()) {
            player.stop()
            player.clearMediaItems()
        } else {
            start(urls[0])
        }
    }
    LaunchedEffect(errors) {
        if (errors == 0) return@LaunchedEffect
        if (errors < urls.size) start(urls[errors]) else failed = true
    }

    val shape = if (rounded) RoundedCornerShape(14.dp) else RoundedCornerShape(0.dp)
    Box(
        modifier
            .clip(shape)
            .background(Brush.radialGradient(listOf(Gold.copy(alpha = 0.30f), Color(0xFF0B0907)))),
        contentAlignment = Alignment.Center,
    ) {
        if (hit != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (failed) Text("ما اشتغلت القناة", color = Color.White)
            else if (buffering) CircularProgressIndicator(Modifier.size(36.dp), color = Gold, strokeWidth = 3.dp)
        } else {
            val img = idle?.channel?.image
            if (img != null) {
                AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(32.dp))
            } else {
                Text("📺", fontSize = 48.sp)
            }
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "● مباشر",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE5484D)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (hit != null) quality?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        // Over the video view, which would otherwise take the touches.
        Box(Modifier.matchParentSize().focusFrame(shape).clickable(onClick = onOpen))
        if (hit != null) {
            Text(
                "اضغط للشاشة الكاملة",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
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
    folders: List<Pair<String, Int>>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    var filter by remember { mutableStateOf("") }
    val fixed = listOf(
        Triple(RECENT, "🕘 سجل المشاهدة", recent),
        Triple(ALL, "الكل", total),
        Triple(FAVORITES, "⭐ المفضلة", favorites),
    ) + folders.map { (name, count) -> Triple(FOLDER + name, "📁 $name", count) }
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
    accountLabel: String?,
    selected: Boolean,
    playing: Boolean,
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
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (playing) Gold else MaterialTheme.colorScheme.onSurface,
            )
            accountLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Gold, maxLines = 1) }
        }
        if (playing) Text("▶", color = Gold, fontWeight = FontWeight.Bold)
        if (favorite) Icon(Icons.Filled.Star, contentDescription = "مفضلة", tint = Gold, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ChannelInfo(
    hit: LiveHit,
    accountLabel: String?,
    categoryName: String?,
    guide: List<XtreamProgram>?,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    val channel = hit.channel
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(channel.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(categoryName, accountLabel).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        when {
            guide == null -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            guide.isEmpty() -> Text("ما فيه دليل برامج لهذي القناة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> GuideList(hit, guide.take(4))
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
                Text("ملء الشاشة", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onFavorite, modifier = Modifier.height(48.dp).focusFrame()) {
                Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null, tint = Gold)
                Spacer(Modifier.width(6.dp))
                Text("المفضلة")
            }
        }
    }
}
