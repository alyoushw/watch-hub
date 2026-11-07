package com.alyoushw.watchhub.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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

    /** Re-fetches every saved item so new episodes and providers show up. */
    fun refresh() {
        if (refreshing) return
        if (container.settings.apiKey.value.isBlank()) return
        viewModelScope.launch {
            refreshing = true
            error = null
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(onOpen: (MediaType, Int) -> Unit) {
    val container = appContainer()
    val vm: WatchlistViewModel = viewModel { WatchlistViewModel(container) }
    val data by container.store.data.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }

    // Shows with new episodes first, then most recently added.
    val items = data.watchlist
        .filter { filter.status == null || it.status == filter.status }
        .sortedWith(
            compareByDescending<WatchItem> { it.status == WatchStatus.WATCHING && it.unwatchedAired() > 0 }
                .thenByDescending { it.addedAt },
        )

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("قائمتي") },
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
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Filter.entries.forEach { f ->
                    val count = data.watchlist.count { f.status == null || it.status == f.status }
                    FilterChip(modifier = Modifier.focusFrame(), selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} ($count)") })
                }
            }
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (items.isEmpty()) {
                MessageBox(
                    title = if (data.watchlist.isEmpty()) "قائمتك فاضية" else "ما فيه شي هنا",
                    body = if (data.watchlist.isEmpty()) "ابحث عن أي فيلم أو مسلسل واضغط «أضفه لقائمتي»." else null,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.key }) { item ->
                        WatchRow(item, onClick = { onOpen(item.type, item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchRow(item: WatchItem, onClick: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val services = item.providerIds.mapNotNull { StreamingService.forProvider(it) }.distinct()

    Card(
        modifier = Modifier.fillMaxWidth().focusHighlight(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Poster(item.posterPath, width = 60.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.type.label + " · " + item.status.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(modifier = Modifier.focusFrame(), onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "خيارات") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                                text = { Text("حذف", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { container.store.removeWatch(item.key) }
                                },
                            )
                        }
                    }
                }
                BadgeRow(services)
                WatchNowButton(item, services)
                if (item.type != MediaType.MOVIE && item.status != WatchStatus.DONE) {
                    EpisodeTracker(item)
                }
            }
        }
    }
}

/** Opens the title directly on its service (or searches for it when no direct link is known). */
@Composable
private fun WatchNowButton(item: WatchItem, services: List<StreamingService>) {
    val context = LocalContext.current
    val service = StreamingService.entries.firstOrNull { it.key in item.directLinks } ?: services.firstOrNull() ?: return
    val direct = item.directLinks[service.key]
    Button(
                    modifier = Modifier.focusFrame(),
        onClick = { context.watchOn(service, direct, item.searchTitle) },
        colors = ButtonDefaults.buttonColors(containerColor = Color(service.color), contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Icon(if (direct != null) Icons.Filled.PlayArrow else Icons.Filled.Search, contentDescription = null, Modifier.size(18.dp))
        Text(" " + if (direct != null) "شاهد على ${service.label}" else "ابحث في ${service.label}")
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
