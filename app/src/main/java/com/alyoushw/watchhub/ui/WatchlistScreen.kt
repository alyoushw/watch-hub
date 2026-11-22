package com.alyoushw.watchhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.StreamingService
import com.alyoushw.watchhub.data.ApiException
import com.alyoushw.watchhub.data.WatchItem
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.data.EpisodeMark
import com.alyoushw.watchhub.data.WatchStatus
import com.alyoushw.watchhub.data.nextEpisode
import com.alyoushw.watchhub.data.previousEpisode
import com.alyoushw.watchhub.data.unwatchedAired
import com.alyoushw.watchhub.data.watchOn
import kotlinx.coroutines.launch

class WatchlistViewModel(private val container: AppContainer) : ViewModel() {
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    /**
     * Where the user really is, from what they played anywhere in the app (IPTV accounts, 4U, watch
     * sources, أنمي فاي): each saved series moves forward to the furthest episode watched.
     */
    private suspend fun syncFromHistory() {
        val episodeNo = Regex("الموسم (\\d+) · الحلقة (\\d+)")
        val watched = container.history.entries.value.filter { it.finished || it.progress >= 0.5f }
        val animeIds = mutableMapOf<String, Int?>()
        val marks = mutableMapOf<Int, EpisodeMark>()
        fun keep(id: Int, mark: EpisodeMark) { if (marks[id]?.let { mark > it } != false) marks[id] = mark }
        for (e in watched) {
            val fromSubtitle = episodeNo.find(e.subtitle.orEmpty())?.let { EpisodeMark(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            val ref = e.animeRef
            when {
                e.sourceRef != null -> e.sourceRef.query.takeIf { it.type == MediaType.TV && it.episode != null }
                    ?.let { q -> keep(q.tmdbId, EpisodeMark(q.season ?: 1, q.episode!!)) }
                e.fourURef != null -> {
                    val id = e.fourURef.detailId ?: continue
                    if (fromSubtitle != null) keep(id, fromSubtitle)
                }
                ref != null -> {
                    val episode = ref.episode.toDoubleOrNull()?.toInt() ?: continue
                    val known = MediaType.fromPath(ref.detailType)?.takeIf { it == MediaType.TV }?.let { ref.detailId }
                    val id = known ?: animeIds.getOrPut(e.title) { findAnimeOnTmdb(container.api, e.title, movie = false)?.id } ?: continue
                    keep(id, EpisodeMark(ref.season.coerceAtLeast(1), episode))
                }
                e.kind == WatchEntry.EPISODE && fromSubtitle != null -> {
                    val id = e.tmdbId ?: container.recommender.tmdbIdFor(MediaType.TV, e.title) ?: continue
                    keep(id, fromSubtitle)
                }
            }
        }
        for (item in container.store.data.value.watchlist) {
            if (item.type == MediaType.MOVIE) continue
            val mark = marks[item.id] ?: continue
            if (item.progress != null && item.progress >= mark) continue
            container.store.updateWatch(item.key) {
                it.copy(progress = mark, status = if (it.status == WatchStatus.PLANNED) WatchStatus.WATCHING else it.status)
            }
        }
    }

    /** Re-fetches every saved item so new episodes and providers show up. */
    fun refresh() {
        if (refreshing) return
        if (container.settings.apiKey.value.isBlank()) return
        viewModelScope.launch {
            refreshing = true
            error = null
            runCatching { syncFromHistory() }
            val region = container.settings.region.value
            for (item in container.store.data.value.watchlist) {
                try {
                    if (item.type == MediaType.ANIME) {
                        val anime = container.jikan.anime(item.id)
                        container.store.updateWatch(item.key) { anime.toWatchItem(it) }
                        continue
                    }
                    val details = container.api.details(item.type, item.id)
                    val links = if (item.directLinks.isEmpty()) container.links.resolve(item.type, item.id) else emptyMap()
                    container.store.updateWatch(item.key) { current ->
                        current.copy(
                            posterPath = details.posterPath ?: current.posterPath,
                            providerIds = details.providersFor(region)?.streaming?.map { it.id } ?: current.providerIds,
                            seasons = details.seasonInfo().ifEmpty { current.seasons },
                            latestAired = details.latestAired() ?: current.latestAired,
                            directLinks = current.directLinks.ifEmpty { links.mapKeys { it.key.key } },
                            anime = current.anime || com.alyoushw.watchhub.data.isAnimeTitle(
                                details.genres.map { it.id }, details.originalLanguage, details.originCountry,
                            ),
                        )
                    }
                } catch (e: ApiException) {
                    error = e.message
                    break
                }
            }
            refreshing = false
        }
    }
}

private enum class Filter(val label: String, val status: WatchStatus?) {
    ALL("الكل", null),
    WATCHING("أتابعه", WatchStatus.WATCHING),
    PLANNED("أبي أشوفه", WatchStatus.PLANNED),
    DONE("خلصته", WatchStatus.DONE),
}

private enum class Kind(val label: String) {
    ALL("الكل"), TV("مسلسلات"), MOVIE("أفلام"), ANIME("أنمي");

    fun matches(item: WatchItem): Boolean = when (this) {
        ALL -> true
        TV -> item.type == MediaType.TV && !item.anime
        MOVIE -> item.type == MediaType.MOVIE
        ANIME -> item.type == MediaType.ANIME || item.anime
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(onOpen: (MediaType, Int) -> Unit) {
    val container = appContainer()
    val vm: WatchlistViewModel = viewModel { WatchlistViewModel(container) }
    val data by container.store.data.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var kind by rememberSaveable { mutableStateOf(Kind.ALL) }

    val ofKind = data.watchlist.filter { kind.matches(it) }
    // Shows with new episodes first, then most recently added.
    val items = ofKind
        .filter { filter.status == null || it.status == filter.status }
        .sortedWith(
            compareByDescending<WatchItem> { it.status == WatchStatus.WATCHING && it.unwatchedAired() > 0 }
                .thenByDescending { it.addedAt },
        )

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("المفضلة") },
                actions = {
                    if (vm.refreshing) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(modifier = Modifier.focusFrame(), onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Type: one capsule, the chosen one gold.
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Kind.entries.forEach { k ->
                    val on = kind == k
                    Text(
                        k.label,
                        textAlign = TextAlign.Center,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) Color(0xFF1C1206) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).focusFrame(CircleShape).clip(CircleShape)
                            .background(if (on) Gold else Color.Transparent)
                            .clickable { kind = k }.padding(vertical = 8.dp),
                    )
                }
            }
            // Status: underlined tabs with their counts.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Filter.entries.forEach { f ->
                    val on = filter == f
                    val count = ofKind.count { f.status == null || it.status == f.status }
                    Column(
                        Modifier.focusFrame(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable { filter = f }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "${f.label}  $count",
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(Modifier.padding(top = 4.dp).height(2.dp).width(if (on) 36.dp else 0.dp).background(Gold))
                    }
                }
            }
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (items.isEmpty()) {
                MessageBox(
                    title = if (data.watchlist.isEmpty()) "المفضلة فاضية" else "ما فيه شي هنا",
                    body = if (data.watchlist.isEmpty()) "ابحث عن أي فيلم أو مسلسل واضغط ♥ عشان تضيفه للمفضلة." else null,
                )
            } else {
                val watchlistState = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyColumn(
                    modifier = Modifier.tvScrollAssist(watchlistState),
                    state = watchlistState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.key }) { item ->
                        WatchRow(item, onClick = { onOpen(item.type, item.id) })
                    }
                }
            }
        }
    }
}

/** One tidy line per title: poster, name, where you are, a gold play button and a ⋮ menu. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WatchRow(item: WatchItem, onClick: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val services = item.providerIds.mapNotNull { StreamingService.forProvider(it) }.distinct()
    val service = StreamingService.entries.firstOrNull { it.key in item.directLinks } ?: services.firstOrNull()
    val progress = item.progress
    val next = item.nextEpisode()
    val unwatched = item.unwatchedAired()
    val where = when {
        item.type == MediaType.MOVIE -> null
        progress == null -> "ما بديت"
        item.type == MediaType.ANIME -> "ح${progress.episode}"
        else -> "م${progress.season} ح${progress.episode}"
    }

    Row(
        Modifier.fillMaxWidth()
            .focusHighlight(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // Long press (a held OK on the TV remote) opens the ⋮ options: the small buttons are hard to reach there.
            .remoteLongPress({ menuOpen = true }, onClick)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Poster(item.posterPath, width = 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildAnnotatedString {
                    append(listOfNotNull(if (item.anime) "أنمي" else item.type.label, where).joinToString(" · "))
                    if (unwatched > 0) withStyle(SpanStyle(color = Gold)) { append(" · باقي $unwatched حلقة") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Play: on its streaming service when known, else the title's page.
        IconButton(
            modifier = Modifier.focusFrame(CircleShape).size(40.dp).clip(CircleShape).background(Gold),
            onClick = { if (service != null) context.watchOn(service, item.directLinks[service.key], item.searchTitle) else onClick() },
        ) { Icon(Icons.Filled.PlayArrow, contentDescription = "شاهد", tint = Color(0xFF1C1206)) }
        Box {
            IconButton(modifier = Modifier.focusFrame(), onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "خيارات") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (item.type != MediaType.MOVIE && next != null) {
                    DropdownMenuItem(
                        text = { Text(if (item.type == MediaType.ANIME) "شفت الحلقة ${next.episode}" else "شفت م${next.season} ح${next.episode}") },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                container.store.updateWatch(item.key) {
                                    it.copy(progress = next, status = if (it.status == WatchStatus.PLANNED) WatchStatus.WATCHING else it.status)
                                }
                            }
                        },
                    )
                }
                if (item.type != MediaType.MOVIE && progress != null) {
                    DropdownMenuItem(
                        text = { Text("تراجع حلقة") },
                        onClick = {
                            menuOpen = false
                            scope.launch { container.store.updateWatch(item.key) { it.copy(progress = it.previousEpisode()) } }
                        },
                    )
                }
                WatchStatus.entries.filter { it != item.status }.forEach { status ->
                    DropdownMenuItem(
                        text = { Text("انقله إلى: ${status.label}") },
                        onClick = {
                            menuOpen = false
                            scope.launch { container.store.updateWatch(item.key) { it.copy(status = status) } }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("إزالة من المفضلة", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        scope.launch { container.store.removeWatch(item.key) }
                    },
                )
            }
        }
    }
}

/** Shows the last watched episode with buttons to move it forward or back. */
@Composable
fun EpisodeTracker(item: WatchItem) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val progress = item.progress
    val next = item.nextEpisode()
    val unwatched = item.unwatchedAired()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when {
                progress == null -> "ما بديت"
                item.type == MediaType.ANIME -> {
                    val total = item.seasons.firstOrNull()?.episodes
                    "وصلت: الحلقة ${progress.episode}" + (total?.let { " من $it" } ?: "")
                }
                else -> "وصلت: الموسم ${progress.season} · الحلقة ${progress.episode}"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (unwatched > 0) {
            Text(
                if (unwatched == 1) "باقي لك حلقة نازلة ما شفتها" else "باقي لك $unwatched حلقات نازلة ما شفتها",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else if (progress != null && item.latestAired != null) {
            Text(
                "أنت ملحّق ✓",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (next != null) {
                FilledTonalButton(
                    modifier = Modifier.focusFrame(),
                    onClick = {
                        scope.launch {
                            container.store.updateWatch(item.key) {
                                it.copy(
                                    progress = next,
                                    status = if (it.status == WatchStatus.PLANNED) WatchStatus.WATCHING else it.status,
                                )
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(if (item.type == MediaType.ANIME) "شفت الحلقة ${next.episode}" else "شفت م${next.season} ح${next.episode}")
                }
            }
            if (progress != null) {
                OutlinedButton(
                    modifier = Modifier.focusFrame(),
                    onClick = { scope.launch { container.store.updateWatch(item.key) { it.copy(progress = it.previousEpisode()) } } },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("تراجع") }
            }
        }
    }
}
