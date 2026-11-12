package com.alyoushw.watchhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.data.*
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.QueueItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

@Serializable
data class AnimeLookup(
    val title: String, val searchTitle: String, val movie: Boolean, val poster: String?,
    val detailType: MediaType, val detailId: Int, val malId: Int? = null,
    val overview: String = "", val overviewIsEnglish: Boolean = false, val backdrop: String? = null,
)

enum class AnimeEpisodeAction { PLAY, DOWNLOAD, DOWNLOAD_SEASON }

class AnimeSourcesViewModel(private val container: AppContainer, private val anime: AnimeLookup) : ViewModel() {
    var titles by mutableStateOf<List<AnimeifyTitle>>(emptyList())
        private set
    var selected by mutableStateOf<AnimeifyTitle?>(null)
        private set
    var episodes by mutableStateOf<List<AnimeifyEpisode>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var episode by mutableStateOf<AnimeifyEpisode?>(null)
        private set
    var servers by mutableStateOf<List<AnimeifyServer>>(emptyList())
        private set
    var serverError by mutableStateOf<String?>(null)
        private set
    var loadingServers by mutableStateOf(false)
        private set
    var action by mutableStateOf(AnimeEpisodeAction.PLAY)
        private set
    private var job: Job? = null
    private var serverJob: Job? = null

    init { reload() }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            error = null
            try {
                val hits = mutableListOf<AnimeifyTitle>()
                for (name in listOf(anime.searchTitle, anime.title).filter { it.isNotBlank() }.distinct()) {
                    hits += container.animeify.search(name, anime.movie)
                    if (anime.malId != null && hits.any { it.malId == anime.malId }) break
                }
                titles = hits.distinctBy { it.id }
                // Never treat a MAL id as an Animeify or TMDB id, or auto-play a fuzzy title match.
                val exact = titles.firstOrNull { anime.malId != null && it.malId == anime.malId }
                    ?: titles.filter { candidate ->
                        candidate.title.equals(anime.searchTitle, ignoreCase = true) || candidate.title.equals(anime.title, ignoreCase = true)
                    }.singleOrNull()
                if (exact != null) loadTitle(exact)
            } catch (e: AnimeifyException) { error = e.message }
            finally { if (isActive) loading = false }
        }
    }

    fun select(title: AnimeifyTitle) {
        job?.cancel()
        dismissEpisode()
        job = viewModelScope.launch {
            loading = true
            error = null
            try { loadTitle(title) }
            catch (e: AnimeifyException) { error = e.message }
            finally { if (isActive) loading = false }
        }
    }

    private suspend fun loadTitle(title: AnimeifyTitle) {
        selected = title
        episodes = emptyList()
        episodes = container.animeify.episodes(title.id)
        // Related works include separately catalogued MAL seasons, films and specials.
        try { titles = (titles + container.animeify.related(title)).distinctBy { it.id } }
        catch (_: AnimeifyException) { /* Current season's episodes remain usable. */ }
    }

    fun chooseEpisode(ep: AnimeifyEpisode, requestedAction: AnimeEpisodeAction = AnimeEpisodeAction.PLAY) {
        action = requestedAction
        serverJob?.cancel()
        episode = ep
        servers = emptyList()
        serverError = null
        loadingServers = true
        serverJob = viewModelScope.launch {
            try { servers = container.animeify.servers(ref(ep, "")) }
            catch (e: AnimeifyException) { serverError = e.message }
            finally { if (isActive) loadingServers = false }
        }
    }

    fun dismissEpisode() {
        serverJob?.cancel()
        episode = null
    }

    private fun ref(ep: AnimeifyEpisode, server: String): AnimeEpisodeRef {
        val title = checkNotNull(selected)
        return AnimeEpisodeRef(title.id, title.malId ?: anime.malId ?: 0, title.type, title.season, ep.number, server,
            detailType = if (title.malId != null) MediaType.ANIME.path else anime.detailType.path,
            detailId = title.malId ?: anime.detailId)
    }

    fun entry(ep: AnimeifyEpisode, server: String): WatchEntry {
        val r = ref(ep, server)
        return WatchEntry(
            key = "animeify:${r.animeId}:${r.episode}", kind = WatchEntry.ANIME,
            itemId = r.episode, title = selected?.title ?: anime.title,
            subtitle = if (r.type == "MOVIE") "فيلم" else "الموسم ${r.season} · الحلقة ${r.episode}", image = anime.backdrop ?: selected?.poster ?: anime.poster,
            seriesId = r.animeId, accountId = "animeify", animeRef = r,
        )
    }

    fun queue(server: String): List<QueueItem> = episodes.map { QueueItem(emptyList(), entry(it, server)) }
}

private val AnimeNavigationJson = Json { ignoreUnknownKeys = true }

fun AnimeLookup.route(): String = "animeify/" + Uri.encode(AnimeNavigationJson.encodeToString(AnimeLookup.serializer(), this))
fun decodeAnimeLookup(value: String): AnimeLookup = AnimeNavigationJson.decodeFromString(AnimeLookup.serializer(), value)

fun JAnime.animeLookup() = AnimeLookup(displayTitle, title, type == "Movie", images?.best,
    MediaType.ANIME, malId, malId, synopsis.orEmpty(), overviewIsEnglish = true)

fun WatchEntry.animeLookup(): AnimeLookup {
    val ref = checkNotNull(animeRef)
    return AnimeLookup(title, title, ref.type == "MOVIE", image,
        MediaType.fromPath(ref.detailType) ?: MediaType.ANIME, ref.detailId,
        ref.malId.takeIf { it > 0 }, backdrop = image)
}

/** The same gold watch action as the existing IPTV buttons; episode controls live on their own page. */
@Composable
fun AnimeifyWatchButton(lookup: AnimeLookup, onOpen: (AnimeLookup) -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = { onOpen(lookup) },
        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206)),
        modifier = modifier.height(44.dp).focusFrame()) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("تشغيل أنمي فاي", fontWeight = FontWeight.Bold)
    }
}

/** Seasons, story, episode cards and the existing download queue, matching the IPTV series page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeSourcesScreen(lookup: AnimeLookup, onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val vm: AnimeSourcesViewModel = viewModel(key = "animeify-${lookup.detailType.path}-${lookup.detailId}") {
        AnimeSourcesViewModel(container, lookup)
    }
    val history by container.history.entries.collectAsStateWithLifecycle()
    val downloads by container.downloads.items.collectAsStateWithLifecycle()
    val title = vm.selected?.title ?: lookup.title
    var showDownloads by remember { mutableStateOf(false) }
    val latest = history.firstOrNull { it.animeRef?.animeId == vm.selected?.id }

    fun pickEpisode(ep: AnimeifyEpisode, action: AnimeEpisodeAction = AnimeEpisodeAction.PLAY) {
        val key = "animeify:${vm.selected?.id}:${ep.number}"
        val local = container.downloads.localUrl(key)
        if (action == AnimeEpisodeAction.PLAY && local != null) {
            val entry = container.downloads.find(key)?.let(container.downloads::entryOf)
            if (entry != null) PlayerActivity.start(context, listOf(QueueItem(listOf(local), entry)), 0)
        } else if (action == AnimeEpisodeAction.DOWNLOAD && downloads.any { it.key == key }) {
            showDownloads = true
        } else {
            vm.chooseEpisode(ep, action)
        }
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.focusFrame()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            } }, actions = { DownloadsButton() })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(1200.dp),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "download-season") {
                OutlinedButton(onClick = {
                    vm.episodes.firstOrNull()?.let { pickEpisode(it, AnimeEpisodeAction.DOWNLOAD_SEASON) }
                }, enabled = vm.episodes.isNotEmpty() && !vm.loading,
                    modifier = Modifier.fillMaxWidth().focusFrame()) {
                    Text("📥 تنزيل الحلقات للمشاهدة بدون نت")
                }
            }
            item(key = "story") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val poster = vm.selected?.poster ?: lookup.poster
                    if (poster != null) AsyncImage(model = poster, contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (lookup.overview.isNotBlank()) TranslatedText(lookup.overview,
                            isEnglish = lookup.overviewIsEnglish, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (vm.selected != null) Text(
                            if (vm.selected?.type == "MOVIE") "فيلم · أنمي فاي" else "الموسم ${vm.selected?.season} · أنمي فاي",
                            color = Gold, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (vm.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            vm.error?.let { error -> item(key = "error") {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.selected?.let(vm::select) ?: vm.reload() }, modifier = Modifier.focusFrame()) { Text("إعادة المحاولة") }
            } }
            if (vm.titles.size > 1 || vm.selected == null && vm.titles.isNotEmpty()) item(key = "seasons") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (vm.selected == null) Text("اختر العمل المطابق:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(vm.titles, key = { it.id }) { season ->
                            FilterChip(selected = vm.selected?.id == season.id, onClick = { vm.select(season) },
                                label = { Text((if (season.type == "MOVIE") "فيلم" else "الموسم ${season.season}") + " · ${season.title}") },
                                modifier = Modifier.focusFrame())
                        }
                    }
                }
            }
            if (!vm.loading && vm.error == null && vm.titles.isEmpty()) item(key = "empty-title") {
                Text("هذا الأنمي غير متوفر في أنمي فاي حالياً.")
            }
            if (!vm.loading && vm.selected != null && vm.error == null && vm.episodes.isEmpty()) item(key = "empty-episodes") {
                Text("لم تتوفر حلقات هذا الموسم بعد.")
            }
            items(vm.episodes, key = { it.number }) { episode ->
                val watched = history.firstOrNull { it.key == "animeify:${vm.selected?.id}:${episode.number}" }
                val number = episode.number.padStart(2, '0')
                val season = (vm.selected?.season ?: 1).toString().padStart(2, '0')
                EpisodePlaybackRow(
                    label = if (vm.selected?.type == "MOVIE") "الفيلم" else "الحلقة ${episode.number}",
                    title = if (vm.selected?.type == "MOVIE") title else "$title S$season E$number" + episode.extra.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    image = null, watched = watched, current = latest?.key == watched?.key && watched != null && !watched.finished,
                    onLongClick = { pickEpisode(episode, AnimeEpisodeAction.DOWNLOAD) },
                    onClick = { pickEpisode(episode) },
                )
            }
        }
    }
    if (showDownloads) DownloadsPage { showDownloads = false }
    vm.episode?.let { episode ->
        val downloading = vm.action != AnimeEpisodeAction.PLAY
        AlertDialog(onDismissRequest = vm::dismissEpisode,
            title = { Text(when (vm.action) {
                AnimeEpisodeAction.PLAY -> if (vm.selected?.type == "MOVIE") "الفيلم · اختر السيرفر" else "الحلقة ${episode.number} · اختر السيرفر"
                AnimeEpisodeAction.DOWNLOAD -> if (vm.selected?.type == "MOVIE") "تنزيل الفيلم" else "تنزيل الحلقة ${episode.number}"
                AnimeEpisodeAction.DOWNLOAD_SEASON -> "تنزيل الموسم · ${vm.episodes.size} حلقة"
            }) }, text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.loadingServers) CircularProgressIndicator()
                    vm.serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!vm.loadingServers && vm.serverError == null && vm.servers.isEmpty()) Text("لا يوجد سيرفر مدعوم لهذه الحلقة حالياً.")
                    if (downloading) Text("اختر الجودة. تظهر الحلقات في التنزيلات وتُحمَّل واحدة بعد الأخرى.", style = MaterialTheme.typography.bodySmall)
                    vm.servers.forEach { server ->
                        Button(onClick = {
                            if (downloading) {
                                val entries = if (vm.action == AnimeEpisodeAction.DOWNLOAD_SEASON)
                                    vm.episodes.map { vm.entry(it, server.key) } else listOf(vm.entry(episode, server.key))
                                val fresh = entries.count { !container.downloads.has(it.key) }
                                container.downloads.startAnime(entries)
                                Toast.makeText(context, if (fresh == 0) "كل الحلقات موجودة في التنزيلات" else "📥 أُضيفت $fresh حلقة إلى التنزيلات", Toast.LENGTH_SHORT).show()
                            } else {
                                PlayerActivity.start(context, vm.queue(server.key), vm.episodes.indexOfFirst { it.number == episode.number })
                            }
                            vm.dismissEpisode()
                        }, modifier = Modifier.fillMaxWidth().focusFrame()) { Text(server.label) }
                    }
                    if (vm.serverError != null) TextButton(onClick = { vm.chooseEpisode(episode, vm.action) }, modifier = Modifier.focusFrame()) { Text("إعادة المحاولة") }
                }
            }, confirmButton = { TextButton(onClick = vm::dismissEpisode, modifier = Modifier.focusFrame()) { Text("إغلاق") } })
    }
}
