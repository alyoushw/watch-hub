package com.alyoushw.watchhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.data.Details
import com.alyoushw.watchhub.data.EpisodeMark
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.ProviderDto
import com.alyoushw.watchhub.data.RegionProviders
import com.alyoushw.watchhub.data.SeasonInfo
import com.alyoushw.watchhub.data.StreamingService
import com.alyoushw.watchhub.data.TmdbApi
import com.alyoushw.watchhub.data.TmdbException
import com.alyoushw.watchhub.data.WatchItem
import com.alyoushw.watchhub.data.WatchStatus
import com.alyoushw.watchhub.data.openUrl
import com.alyoushw.watchhub.data.searchOn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class DetailInfo(
    val type: MediaType,
    val id: Int,
    val title: String,
    val searchTitle: String,
    val year: String?,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double?,
    val genres: List<String>,
    val region: String,
    val providers: RegionProviders?,
    val seasons: List<SeasonInfo>,
    val latestAired: EpisodeMark?,
) {
    fun toWatchItem(existing: WatchItem?): WatchItem {
        val base = existing ?: WatchItem(id = id, type = type, title = title)
        return base.copy(
            title = title,
            searchTitle = searchTitle,
            posterPath = posterPath,
            providerIds = providers?.streaming?.map { it.id } ?: base.providerIds,
            seasons = seasons.ifEmpty { base.seasons },
            latestAired = latestAired ?: base.latestAired,
        )
    }

    companion object {
        fun from(type: MediaType, ar: Details, en: Details?, region: String) = DetailInfo(
            type = type,
            id = ar.id,
            title = ar.displayTitle.ifBlank { en?.displayTitle.orEmpty() },
            searchTitle = en?.displayTitle?.ifBlank { null } ?: ar.displayTitle,
            year = ar.year,
            overview = ar.overview?.ifBlank { null } ?: en?.overview.orEmpty(),
            posterPath = ar.posterPath ?: en?.posterPath,
            backdropPath = ar.backdropPath ?: en?.backdropPath,
            rating = ar.voteAverage?.takeIf { it > 0 },
            genres = ar.genres.map { it.name }.filter { it.isNotBlank() },
            region = region,
            providers = ar.providersFor(region),
            seasons = ar.seasonInfo(),
            latestAired = ar.latestAired(),
        )
    }
}

sealed interface DetailState {
    data object Loading : DetailState
    data class Loaded(val info: DetailInfo) : DetailState
    data class Failed(val message: String) : DetailState
}

class DetailViewModel(
    private val container: AppContainer,
    private val type: MediaType,
    private val id: Int,
) : ViewModel() {
    var state by mutableStateOf<DetailState>(DetailState.Loading)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state = DetailState.Loading
            state = try {
                coroutineScope {
                    val ar = async { container.api.details(type, id) }
                    val en = async { runCatching { container.api.englishDetails(type, id) }.getOrNull() }
                    val info = DetailInfo.from(type, ar.await(), en.await(), container.settings.region.value)
                    // Keep the saved copy fresh (new episodes, providers) whenever it is opened.
                    val saved = container.store.data.value.watchlist.firstOrNull { it.key == WatchItem.keyOf(type, id) }
                    if (saved != null) container.store.upsertWatch(info.toWatchItem(saved))
                    DetailState.Loaded(info)
                }
            } catch (e: TmdbException) {
                DetailState.Failed(e.message ?: "صار خطأ")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(type: MediaType, id: Int, onBack: () -> Unit) {
    val container = appContainer()
    val vm: DetailViewModel = viewModel { DetailViewModel(container, type, id) }
    val title = (vm.state as? DetailState.Loaded)?.info?.title.orEmpty()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = vm.state) {
                DetailState.Loading -> CenteredLoading()
                is DetailState.Failed -> MessageBox(s.message, actionLabel = "حاول مرة ثانية", onAction = vm::load)
                is DetailState.Loaded -> DetailContent(s.info)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(info: DetailInfo) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by container.store.data.collectAsStateWithLifecycle()
    val saved = data.watchlist.firstOrNull { it.key == WatchItem.keyOf(info.type, info.id) }
    val subscribedKeys = data.subscriptions.mapNotNull { it.serviceKey }.toSet()

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Header(info)
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saved == null) {
                    Button(
                        onClick = { scope.launch { container.store.upsertWatch(info.toWatchItem(null)) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("  أضفه لقائمتي")
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WatchStatus.entries.forEach { status ->
                            FilterChip(
                                selected = saved.status == status,
                                onClick = { scope.launch { container.store.updateWatch(saved.key) { it.copy(status = status) } } },
                                label = { Text(status.label) },
                                leadingIcon = if (saved.status == status) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp)) }
                                } else null,
                            )
                        }
                    }
                    if (info.type == MediaType.TV) {
                        EpisodeTracker(saved)
                    }
                    TextButton(onClick = { scope.launch { container.store.removeWatch(saved.key) } }) {
                        Text("شيله من قائمتي", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            WhereToWatch(info, subscribedKeys) { service -> context.searchOn(service, info.searchTitle) }
        }

        if (info.overview.isNotBlank()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle("القصة")
                    Text(info.overview, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (info.genres.isNotEmpty()) {
            item {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    info.genres.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }
        }

        item {
            Column {
                val link = info.providers?.link
                if (link != null) {
                    TextButton(onClick = { context.openUrl(link) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("كل خيارات المشاهدة على TMDB")
                    }
                }
                Text(
                    "بيانات التوفر من JustWatch عبر TMDB، وقد لا تكون محدّثة دائماً.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(info: DetailInfo) {
    Column {
        if (info.backdropPath != null) {
            AsyncImage(
                model = TmdbApi.image(info.backdropPath, "w780"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        }
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(info.posterPath, width = 90.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(info.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (info.searchTitle != info.title) {
                    Text(info.searchTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val kind = if (info.type == MediaType.TV) "مسلسل" else "فيلم"
                val seasons = if (info.type == MediaType.TV && info.seasons.isNotEmpty()) "${info.seasons.size} مواسم" else null
                val rating = info.rating?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }
                Text(
                    listOfNotNull(kind, info.year, seasons, rating).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhereToWatch(info: DetailInfo, subscribedKeys: Set<String>, onWatch: (StreamingService) -> Unit) {
    val context = LocalContext.current
    val streaming = info.providers?.streaming.orEmpty()
    val paid = info.providers?.paid.orEmpty()

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("وين أشوفه؟ (${info.region})")

        if (streaming.isEmpty()) {
            Text(
                "ما لقينا منصة بث فيها هذا العمل في منطقتك.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        streaming.forEach { provider ->
            ProviderRow(provider, subscribedKeys) { service ->
                if (service != null) onWatch(service) else info.providers?.link?.let { context.openUrl(it) }
            }
        }

        if (paid.isNotEmpty()) {
            Text(
                "للإيجار أو الشراء: " + paid.joinToString("، ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // TMDB data can miss things (especially anime), so always offer a direct search.
        Text("ابحث عنه مباشرة في:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreamingService.entries.forEach { service ->
                OutlinedButton(onClick = { onWatch(service) }) { Text(service.label) }
            }
        }
    }
}

@Composable
private fun ProviderRow(provider: ProviderDto, subscribedKeys: Set<String>, onWatch: (StreamingService?) -> Unit) {
    val service = StreamingService.forProvider(provider.id)
    val subscribed = service != null && service.key in subscribedKeys
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = TmdbApi.image(provider.logoPath, "w92"),
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f)) {
                Text(service?.label ?: provider.name, fontWeight = FontWeight.Bold)
                if (subscribed) {
                    Text("✓ ضمن اشتراكاتك", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Button(
                onClick = { onWatch(service) },
                colors = if (service != null) ButtonDefaults.buttonColors(containerColor = Color(service.color)) else ButtonDefaults.buttonColors(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Text(" شاهد")
            }
        }
    }
}
