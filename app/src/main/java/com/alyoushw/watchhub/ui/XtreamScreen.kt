package com.alyoushw.watchhub.ui

import com.alyoushw.watchhub.data.MediaType
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import com.alyoushw.watchhub.data.XtreamAccount
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.alyoushw.watchhub.data.XtreamSeries
import com.alyoushw.watchhub.data.XtreamEpisode
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Channels, films and series of the user's Xtream Codes account, one category at a time. */
class XtreamViewModel(private val api: XtreamApi, start: XtreamKind = XtreamKind.LIVE) : ViewModel() {
    var kind by mutableStateOf(start)
        private set
    var categories by mutableStateOf<List<XtreamCategory>>(emptyList())
        private set
    var selected by mutableStateOf<String?>(null)
        private set
    var items by mutableStateOf<List<XtreamItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    init {
        selectKind(start)
    }

    fun selectKind(value: XtreamKind) {
        kind = value
        load(categoryId = null, reloadCategories = true)
    }

    fun selectCategory(id: String) {
        if (id == selected && items.isNotEmpty()) return
        load(categoryId = id, reloadCategories = false)
    }

    fun retry() = load(selected, reloadCategories = categories.isEmpty())

    private fun load(categoryId: String?, reloadCategories: Boolean) {
        job?.cancel()
        val k = kind
        job = viewModelScope.launch {
            loading = true
            error = null
            items = emptyList()
            if (reloadCategories) {
                categories = emptyList()
                selected = null
            }
            // Live channels have their own screen (LiveChannels).
            if (k == XtreamKind.LIVE) {
                loading = false
                return@launch
            }
            try {
                if (reloadCategories) categories = api.categories(k)
                val id = categoryId ?: categories.firstOrNull()?.id
                selected = id
                items = api.items(k, id)
            } catch (e: XtreamException) {
                error = e.message
            }
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XtreamScreen(onOpenSeries: (String) -> Unit, onOpenSettings: () -> Unit) {
    val container = appContainer()
    var showDownloads by remember { mutableStateOf(false) }
    val account by container.settings.xtream.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("قنواتي") },
                actions = {
                    androidx.compose.material3.TextButton(modifier = Modifier.focusFrame(), onClick = { showDownloads = true }) { Text("📥 التنزيلات") }
                },
            )
        },
    ) { padding ->
        if (showDownloads) DownloadsDialog(onClose = { showDownloads = false })
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!account.isComplete) {
                MessageBox(
                    "أضف حساب Xtream Codes من الإعدادات عشان يطلع لك محتواه هنا",
                    actionLabel = "افتح الإعدادات",
                    onAction = onOpenSettings,
                )
            } else {
                XtreamContent(onOpenSeries)
            }
        }
    }
}

/** Live channels only, for the "مباشر" tab on the Home screen. */
@Composable
fun XtreamLiveChannels(onOpenSettings: () -> Unit) {
    val account by appContainer().settings.xtream.collectAsStateWithLifecycle()
    if (account.isComplete) {
        LiveChannels()
    } else {
        MessageBox(
            title = "أضف حساب Xtream Codes من الإعدادات عشان تطلع لك القنوات المباشرة هنا",
            actionLabel = "افتح الإعدادات",
            onAction = onOpenSettings,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XtreamContent(onOpenSeries: (String) -> Unit, kinds: List<XtreamKind> = XtreamKind.entries) {
    val container = appContainer()
    val context = LocalContext.current
    val account by container.settings.xtream.collectAsStateWithLifecycle()
    // A new login starts a fresh list.
    val vm: XtreamViewModel = viewModel(key = "xtream:${kinds.joinToString()}:${account.host}:${account.username}") {
        XtreamViewModel(container.xtream, kinds.first())
    }
    var filter by remember(vm.kind, vm.selected) { mutableStateOf("") }
    var itemMenu by remember { mutableStateOf<XtreamItem?>(null) }
    val scope = rememberCoroutineScope()

    // Long press on a film or series: share it; films can also be saved for watching offline.
    itemMenu?.let { item ->
        AlertDialog(
            onDismissRequest = { itemMenu = null },
            title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.fillMaxWidth().focusFrame(), onClick = {
                        itemMenu = null
                        scope.launch {
                            context.shareByName(container.api, item.name, if (item.kind == XtreamKind.SERIES) MediaType.TV else MediaType.MOVIE, item.tmdb?.toIntOrNull())
                        }
                    }) { Text("↗ مشاركة") }
                    if (item.kind == XtreamKind.MOVIE) {
                        val e = WatchEntry.movie(item, account)
                        val saved = container.downloads.has(e.key)
                        OutlinedButton(modifier = Modifier.fillMaxWidth().focusFrame(), onClick = {
                            itemMenu = null
                            if (saved) container.downloads.remove(e.key) else container.downloads.start(e, container.xtream.playUrls(item, account).first())
                            android.widget.Toast.makeText(context, if (saved) "انحذف التنزيل" else "📥 بدأ تنزيل «${item.name}»", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text(if (saved) "🗑 احذف التنزيل" else "📥 نزّله للمشاهدة بدون نت") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { itemMenu = null }) { Text("إلغاء") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        AccountSwitcher(Modifier.padding(vertical = 6.dp))
        if (kinds.size > 1) {
            PrimaryTabRow(selectedTabIndex = kinds.indexOf(vm.kind).coerceAtLeast(0)) {
                kinds.forEach { k ->
                    Tab(
                        modifier = Modifier.focusFrame(RoundedCornerShape(8.dp)),
                        selected = vm.kind == k,
                        onClick = { if (vm.kind != k) vm.selectKind(k) },
                        text = { Text(k.label) },
                    )
                }
            }
        }
        if (vm.kind == XtreamKind.LIVE) {
            LiveChannels(showAccounts = false)
        } else {
            if (vm.categories.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(vm.categories, key = { it.id }) { c ->
                        FilterChip(
                            modifier = Modifier.focusFrame(),
                            selected = vm.selected == c.id,
                            onClick = { vm.selectCategory(c.id) },
                            label = { Text(c.name, maxLines = 1) },
                        )
                    }
                }
            }
            if (vm.items.isNotEmpty()) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("ابحث في هذا القسم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val shown = remember(vm.items, filter) {
                    val q = filter.trim()
                    if (q.isEmpty()) vm.items else vm.items.filter { it.name.contains(q, ignoreCase = true) }
                }
                when {
                    vm.loading -> CenteredLoading()
                    vm.error != null -> MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::retry)
                    vm.items.isEmpty() -> MessageBox("ما فيه شي في هذا القسم")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (vm.kind == XtreamKind.LIVE) 120.dp else 112.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "${shown.size} ${if (vm.kind == XtreamKind.LIVE) "قناة" else "عمل"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(shown, key = { "${it.kind}:${it.id}" }) { item ->
                            XtreamCard(item, onLongClick = { if (item.kind != XtreamKind.LIVE) itemMenu = item }) {
                                if (item.kind == XtreamKind.SERIES) {
                                    onOpenSeries(item.id)
                                } else {
                                    PlayerActivity.start(context, container.xtream.playUrls(item, account), WatchEntry.movie(item, account))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun XtreamCard(item: XtreamItem, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .focusHighlight(shape)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (item.kind == XtreamKind.LIVE) 1.4f else 2f / 3f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (item.image != null) {
                AsyncImage(
                    model = item.image,
                    contentDescription = null,
                    contentScale = if (item.kind == XtreamKind.LIVE) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (item.kind == XtreamKind.LIVE) 10.dp else 0.dp),
                )
            } else {
                Text(if (item.kind == XtreamKind.LIVE) "📺" else "🎬", style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Seasons and episodes of one series from the account. */
class XtreamSeriesViewModel(private val api: XtreamApi, private val seriesId: String) : ViewModel() {
    var series by mutableStateOf<XtreamSeries?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            try {
                series = api.series(seriesId)
            } catch (e: XtreamException) {
                error = e.message
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XtreamSeriesScreen(seriesId: String, onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val vm: XtreamSeriesViewModel = viewModel(key = "xtream-series:$seriesId") {
        XtreamSeriesViewModel(container.xtream, seriesId)
    }
    val series = vm.series
    val history by container.history.entries.collectAsStateWithLifecycle()
    val account by container.settings.xtream.collectAsStateWithLifecycle()
    val playEpisode: (XtreamEpisode) -> Unit = { ep ->
        series?.let { sr ->
            // The whole series as a queue: "الحلقة الجاية" goes on through the episodes.
            val items = sr.episodes.map { e ->
                com.alyoushw.watchhub.player.QueueItem(container.xtream.playUrls(e, account), WatchEntry.episode(account, seriesId, sr.name, sr.cover, e))
            }
            PlayerActivity.start(context, items, sr.episodes.indexOfFirst { it.id == ep.id }.coerceAtLeast(0))
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(series?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(modifier = Modifier.focusFrame(), onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                vm.error != null -> MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::load)
                series == null -> CenteredLoading()
                series.episodes.isEmpty() -> MessageBox("ما فيه حلقات لهذا المسلسل")
                else -> {
                    val seasons = remember(series) { series.episodes.map { it.season }.distinct() }
                    // Where the user is in this series: the last episode played, or the next one if it was finished.
                    val resume = remember(series, history) {
                        val last = history.firstOrNull { it.kind == WatchEntry.EPISODE && it.seriesId == seriesId && it.accountId == account.id }
                        val i = series.episodes.indexOfFirst { it.id == last?.itemId }
                        when {
                            i < 0 -> null
                            last?.finished == true -> series.episodes.getOrNull(i + 1)
                            else -> series.episodes[i]
                        }
                    }
                    var season by remember(series) { mutableStateOf(resume?.season ?: seasons.first()) }
                    var markFor by remember { mutableStateOf<XtreamEpisode?>(null) }
                    val shareScope = rememberCoroutineScope()

                    // Long press on an episode: watched / not watched (synced like any progress).
                    markFor?.let { ep ->
                        val key = WatchEntry.keyOf(account, WatchEntry.EPISODE, ep.id)
                        val entry = history.firstOrNull { it.key == key }
                        fun markWatched(e: XtreamEpisode) {
                            val d = entry?.durationMs?.takeIf { it > 0 } ?: 1L
                            val base = entry ?: WatchEntry.episode(account, seriesId, series.name, series.cover, e)
                            container.history.save(base.copy(positionMs = d, durationMs = d, updatedAt = System.currentTimeMillis()), important = true)
                        }
                        AlertDialog(
                            onDismissRequest = { markFor = null },
                            title = { Text("الموسم ${ep.season} · الحلقة ${ep.number}") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val state = when {
                                        entry?.finished == true -> "حالتها: تم المشاهدة"
                                        entry != null && entry.durationMs > 0 -> "حالتها: جاري المشاهدة"
                                        else -> "حالتها: ما شفتها"
                                    }
                                    Text(state, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth().focusFrame(),
                                        onClick = {
                                            markWatched(ep)
                                            markFor = null
                                        },
                                    ) { Text("✓ تم المشاهدة") }
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth().focusFrame(),
                                        onClick = {
                                            container.history.remove(key)
                                            markFor = null
                                        },
                                    ) { Text("لم أشاهدها") }
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth().focusFrame(),
                                        onClick = {
                                            markFor = null
                                            shareScope.launch { context.shareByName(container.api, series.name, MediaType.TV) }
                                        },
                                    ) { Text("↗ مشاركة المسلسل") }
                                    val epEntry = WatchEntry.episode(account, seriesId, series.name, series.cover, ep)
                                    val saved = container.downloads.has(epEntry.key)
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth().focusFrame(),
                                        onClick = {
                                            if (saved) container.downloads.remove(epEntry.key) else container.downloads.start(epEntry, container.xtream.playUrls(ep, account).first())
                                            android.widget.Toast.makeText(context, if (saved) "انحذف التنزيل" else "📥 بدأ التنزيل، تلقاه في «التنزيلات»", android.widget.Toast.LENGTH_SHORT).show()
                                            markFor = null
                                        },
                                    ) { Text(if (saved) "🗑 احذف التنزيل" else "📥 نزّلها للمشاهدة بدون نت") }
                                }
                            },
                            confirmButton = {},
                            dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { markFor = null }) { Text("إلغاء") } },
                        )
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        resume?.let { ep ->
                            item(key = "resume") {
                                Button(
                                    onClick = { playEpisode(ep) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206)),
                                    modifier = Modifier.fillMaxWidth().height(50.dp).focusFrame(),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("كمّل: الموسم ${ep.season} · الحلقة ${ep.number}", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (series.cover != null) {
                                    AsyncImage(
                                        model = series.cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(8.dp)),
                                    )
                                }
                                series.plot?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        if (seasons.size > 1) {
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(seasons) { s ->
                                        FilterChip(
                                            modifier = Modifier.focusFrame(),
                                            selected = season == s,
                                            onClick = { season = s },
                                            label = { Text("الموسم $s") },
                                        )
                                    }
                                }
                            }
                        }
                        items(series.episodes.filter { it.season == season }, key = { it.id }) { ep ->
                            val watched = history.firstOrNull { it.key == WatchEntry.keyOf(account, WatchEntry.EPISODE, ep.id) }
                            EpisodeRow(
                                ep,
                                watched,
                                current = ep.id == resume?.id && watched?.finished != true,
                                onLongClick = { markFor = ep },
                            ) { playEpisode(ep) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "مشاهدة" next to the poster on a title page: plays the film (or opens the series) straight
 * from the user's Xtream Codes accounts. With several accounts there is one button for each
 * account that has it ("مشاهدة · <name>").
 */
@Composable
fun XtreamWatchButton(
    isSeries: Boolean,
    tmdbId: Int,
    titles: List<String>,
    year: String?,
    onOpenSeries: (String) -> Unit,
) {
    val container = appContainer()
    val context = LocalContext.current
    val accounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    val usable = accounts.filter { it.isComplete }
    if (usable.isEmpty()) return

    var searching by remember(tmdbId, usable) { mutableStateOf(true) }
    var matches by remember(tmdbId, usable) { mutableStateOf<List<Pair<XtreamAccount, XtreamItem>>>(emptyList()) }
    LaunchedEffect(tmdbId, usable) {
        matches = coroutineScope {
            usable.map { acc ->
                async {
                    try {
                        container.xtream.findMatch(if (isSeries) XtreamKind.SERIES else XtreamKind.MOVIE, tmdbId, titles, year, acc)?.let { acc to it }
                    } catch (e: XtreamException) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        searching = false
    }

    val colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206))
    when {
        searching -> Button(onClick = {}, enabled = false, colors = colors, modifier = Modifier.height(44.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(if (usable.size > 1) "نبحث في حساباتك…" else "نبحث في حسابك…")
        }
        matches.isEmpty() -> Button(onClick = {}, enabled = false, colors = colors, modifier = Modifier.height(44.dp)) {
            Text(if (usable.size > 1) "مو موجود في حساباتك" else "مو موجود في حسابك")
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            matches.forEach { (acc, found) ->
                Button(
                    onClick = {
                        if (found.kind == XtreamKind.SERIES) {
                            // The series page shows the active account, so switch to the one that has it.
                            container.settings.setActiveXtream(acc.id)
                            onOpenSeries(found.id)
                        } else {
                            PlayerActivity.start(context, container.xtream.playUrls(found, acc), WatchEntry.movie(found, acc, tmdbId))
                        }
                    },
                    colors = colors,
                    modifier = Modifier.height(44.dp).focusFrame(),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (usable.size > 1) "مشاهدة · ${acc.label}" else "مشاهدة", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** An episode in the list: gold frame and "تشاهدها" while in progress, faded with ✓ once finished. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(ep: XtreamEpisode, watched: WatchEntry?, current: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    val finished = watched?.finished == true
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(shape)
            .clip(shape)
            .background(if (current) Gold.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(if (current) 2.dp else 0.dp, if (current) Gold else Color.Transparent, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .graphicsLayer { alpha = if (finished) 0.55f else 1f }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (ep.image != null) {
                AsyncImage(model = ep.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (finished) Color(0xFF2E7D4F) else Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                if (finished) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) else Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            if (watched != null && !finished) ProgressStrip(watched, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الحلقة ${ep.number}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                when {
                    finished -> Tag("✓ خلصتها", Color(0xFF2E7D4F))
                    current || (watched != null && watched.durationMs > 0) -> Tag("▶ تشاهدها", Gold, dark = true)
                }
            }
            Text(ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (watched != null && !finished && watched.durationMs > 0) {
                Text(
                    "وصلت ${com.alyoushw.watchhub.player.formatTime(watched.positionMs)} من ${com.alyoushw.watchhub.player.formatTime(watched.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color, dark: Boolean = false) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = if (dark) Color(0xFF1C1206) else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Chips to pick which Xtream account the channels / films / series screens show (only with 2+). */
@Composable
fun AccountSwitcher(modifier: Modifier = Modifier) {
    val container = appContainer()
    val accounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    val active by container.settings.xtream.collectAsStateWithLifecycle()
    if (accounts.size < 2) return
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(accounts, key = { it.id }) { acc ->
            FilterChip(
                modifier = Modifier.focusFrame(),
                selected = acc.id == active.id,
                onClick = { container.settings.setActiveXtream(acc.id) },
                label = { Text(acc.label, maxLines = 1) },
            )
        }
    }
}

/** Thin gold bar along the bottom of a thumbnail: how much of it was watched. */
@Composable
fun ProgressStrip(entry: WatchEntry, modifier: Modifier = Modifier) {
    if (entry.isLive || entry.durationMs <= 0) return
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.55f))) {
        Box(Modifier.fillMaxWidth(entry.progress).height(4.dp).background(Gold))
    }
}

/** "تابع المشاهدة": channels, films and episodes played lately, resuming where the user stopped. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueWatchingRow(
    onOpenSeries: (String) -> Unit,
    /** Which kinds this row shows: episodes on the series tab, films on the films tab. */
    kinds: Set<String> = setOf(WatchEntry.EPISODE, WatchEntry.MOVIE),
    onOpenTitle: (MediaType, Int) -> Unit = { _, _ -> },
    onOpenLive: () -> Unit = {},
) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    val entries by container.history.entries.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<WatchEntry?>(null) }
    if (accounts.isEmpty()) return
    val list = remember(entries, accounts, kinds) {
        val ids = accounts.map { it.id }.toSet()
        container.history.continueList().filter { it.accountId in ids && it.kind in kinds }.take(20)
    }
    if (list.isEmpty()) return

    fun openSeries(e: WatchEntry) {
        e.accountId?.let { container.settings.setActiveXtream(it) }
        e.seriesId?.let(onOpenSeries)
    }

    /** The title's page on the platform side (TMDB): its known id, else found by its name. */
    fun openPlatformPage(e: WatchEntry) {
        val type = if (e.kind == WatchEntry.EPISODE) MediaType.TV else MediaType.MOVIE
        val known = e.tmdbId
        if (known != null && type == MediaType.MOVIE) {
            onOpenTitle(type, known)
            return
        }
        scope.launch {
            val name = XtreamApi.normalizeTitle(e.title)
            val hit = runCatching { container.api.search(name).firstOrNull { it.type == type } }.getOrNull()
            if (hit != null) {
                onOpenTitle(type, hit.id)
            } else {
                android.widget.Toast.makeText(context, "ما لقينا صفحة «$name» في المنصة", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Long press: open the title's own page, or drop it from the row.
    menuFor?.let { e ->
        val openLabel = when (e.kind) {
            WatchEntry.EPISODE -> "حلقات المسلسل في الحساب"
            WatchEntry.MOVIE -> "معلومات الفيلم (المنصة)"
            else -> "افتح القنوات المباشرة"
        }
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().focusFrame(),
                        onClick = {
                            menuFor = null
                            when (e.kind) {
                                WatchEntry.EPISODE -> openSeries(e)
                                WatchEntry.LIVE -> {
                                    e.accountId?.let { container.settings.setActiveXtream(it) }
                                    onOpenLive()
                                }
                                else -> openPlatformPage(e)
                            }
                        },
                    ) { Text(openLabel) }
                    if (e.kind == WatchEntry.EPISODE) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().focusFrame(),
                            onClick = {
                                menuFor = null
                                openPlatformPage(e)
                            },
                        ) { Text("معلومات المسلسل (المنصة)") }
                    }
                    if (e.kind != WatchEntry.LIVE) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().focusFrame(),
                            onClick = {
                                menuFor = null
                                scope.launch {
                                    context.shareByName(container.api, e.title, if (e.kind == WatchEntry.EPISODE) MediaType.TV else MediaType.MOVIE, e.tmdbId)
                                }
                            },
                        ) { Text("↗ مشاركة") }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().focusFrame(),
                        onClick = {
                            container.history.remove(e.key)
                            menuFor = null
                            android.widget.Toast.makeText(context, "انحذف من «تابع المشاهدة»", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("احذف من تابع المشاهدة", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { menuFor = null }) { Text("إلغاء") } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("تابع المشاهدة", Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.key }) { e ->
                val shape = RoundedCornerShape(12.dp)
                Column(
                    Modifier
                        .width(220.dp)
                        .focusHighlight(shape)
                        .clip(shape)
                        .combinedClickable(
                            onClick = {
                                if (e.kind == WatchEntry.EPISODE && e.finished && e.seriesId != null) {
                                    openSeries(e)
                                } else {
                                    PlayerActivity.start(context, container.xtream.playUrls(e), e)
                                }
                            },
                            onLongClick = { menuFor = e },
                        ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (e.image != null) {
                            AsyncImage(
                                model = e.image,
                                contentDescription = null,
                                contentScale = if (e.isLive) ContentScale.Fit else ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().padding(if (e.isLive) 18.dp else 0.dp),
                            )
                        }
                        Box(Modifier.size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                        }
                        if (e.isLive) {
                            Text(
                                "● مباشر",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xFFE5484D)).padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        ProgressStrip(e, Modifier.align(Alignment.BottomCenter))
                    }
                    Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    val sub = e.subtitle ?: when {
                        e.isLive -> "قناة مباشرة"
                        e.durationMs > 0 -> "باقي ${com.alyoushw.watchhub.player.formatTime(e.durationMs - e.positionMs)}"
                        else -> null
                    }
                    // Same height for every card (empty line when there is nothing to say).
                    Text(sub ?: " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (accounts.size > 1) {
                        accounts.firstOrNull { it.id == e.accountId }?.let {
                            Text(it.label, style = MaterialTheme.typography.labelSmall, color = Gold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** "📥 التنزيلات": films and episodes saved on the device; play without internet or delete. */
@Composable
private fun DownloadsDialog(onClose: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val saved by container.downloads.items.collectAsStateWithLifecycle()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_500)
            tick++
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("📥 التنزيلات") },
        text = {
            if (saved.isEmpty()) {
                Text("ما فيه تنزيلات. اضغط مطولاً على أي فيلم، أو على حلقة في صفحة المسلسل، واختر «نزّلها».")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(saved, key = { it.key }) { d ->
                        val st = remember(tick, d.key) { container.downloads.state(d) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(d.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        st.failed -> "⚠️ فشل التنزيل"
                                        st.done -> listOfNotNull(d.subtitle, "✓ جاهز").joinToString(" · ")
                                        else -> "ننزّل… ${Math.round(st.progress * 100)}%"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            if (st.done) {
                                TextButton(modifier = Modifier.focusFrame(), onClick = {
                                    val e = container.downloads.entryOf(d)
                                    val url = container.downloads.localUrl(d.key)
                                    if (e != null && url != null) {
                                        onClose()
                                        PlayerActivity.start(context, listOf(url), e)
                                    }
                                }) { Text("▶") }
                            }
                            TextButton(modifier = Modifier.focusFrame(), onClick = { container.downloads.remove(d.key) }) { Text("🗑") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.focusFrame(), onClick = onClose) { Text("تم") } },
    )
}
