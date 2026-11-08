package com.alyoushw.watchhub.ui

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
    val account by container.settings.xtream.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("قنواتي") }) },
    ) { padding ->
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

    Column(Modifier.fillMaxSize()) {
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
            LiveChannels()
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
                            XtreamCard(item) {
                                if (item.kind == XtreamKind.SERIES) {
                                    onOpenSeries(item.id)
                                } else {
                                    PlayerActivity.start(context, container.xtream.playUrls(item), item.name)
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
private fun XtreamCard(item: XtreamItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .focusHighlight(shape)
            .clip(shape)
            .clickable(onClick = onClick),
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
                    var season by remember(series) { mutableStateOf(seasons.first()) }
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
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
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .focusHighlight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        PlayerActivity.start(context, container.xtream.playUrls(ep), "${series.name} - ${ep.title}")
                                    }
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (ep.image != null) {
                                        AsyncImage(
                                            model = ep.image,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Text("▶")
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("الحلقة ${ep.number}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                    Text(ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "مشاهدة" next to the poster on a title page: plays the film (or opens the series) straight
 * from the user's Xtream Codes account when it is there.
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
    val account by container.settings.xtream.collectAsStateWithLifecycle()
    if (!account.isComplete) return

    var searching by remember(tmdbId, account) { mutableStateOf(true) }
    var match by remember(tmdbId, account) { mutableStateOf<XtreamItem?>(null) }
    LaunchedEffect(tmdbId, account) {
        match = try {
            container.xtream.findMatch(if (isSeries) XtreamKind.SERIES else XtreamKind.MOVIE, tmdbId, titles, year)
        } catch (e: XtreamException) {
            null
        }
        searching = false
    }

    val found = match
    Button(
        enabled = found != null,
        onClick = {
            if (found == null) return@Button
            if (found.kind == XtreamKind.SERIES) {
                onOpenSeries(found.id)
            } else {
                PlayerActivity.start(context, container.xtream.playUrls(found), found.name)
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206)),
        modifier = Modifier.height(44.dp).focusFrame(),
    ) {
        when {
            searching -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("نبحث في حسابك…")
            }
            found == null -> Text("مو موجود في حسابك")
            else -> {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("مشاهدة", fontWeight = FontWeight.Bold)
            }
        }
    }
}
