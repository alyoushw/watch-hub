package com.alyoushw.watchhub.ui

import androidx.compose.foundation.layout.*
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

data class AnimeLookup(
    val title: String, val searchTitle: String, val movie: Boolean, val poster: String?,
    val detailType: MediaType, val detailId: Int, val malId: Int? = null,
)

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

    fun chooseEpisode(ep: AnimeifyEpisode) {
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

    fun queue(server: String): List<QueueItem> = episodes.map { ep ->
        val r = ref(ep, server)
        QueueItem(emptyList(), WatchEntry(
            key = "animeify:${r.animeId}:${r.episode}", kind = WatchEntry.ANIME,
            itemId = r.episode, title = selected?.title ?: anime.title,
            subtitle = if (r.type == "MOVIE") "فيلم" else "الموسم ${r.season} · الحلقة ${r.episode}", image = anime.poster,
            seriesId = r.animeId, accountId = "animeify", animeRef = r,
        ))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimeSources(anime: JAnime) = AnimeSources(AnimeLookup(
    anime.displayTitle, anime.title, anime.type == "Movie", anime.images?.best, MediaType.ANIME, anime.malId, anime.malId,
))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimeSources(anime: AnimeLookup) {
    val container = appContainer()
    val context = LocalContext.current
    val vm: AnimeSourcesViewModel = viewModel(key = "animeify-${anime.detailType.path}-${anime.detailId}") { AnimeSourcesViewModel(container, anime) }
    val history by container.history.entries.collectAsStateWithLifecycle()
    var page by remember(vm.selected?.id) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("حلقات Animeify")
        Text("المواسم والسيرفرات حسب توفر المصدر. العربية في النسخة المترجمة أو من قائمة الترجمات في المشغل.",
            style = MaterialTheme.typography.bodySmall)
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { vm.selected?.let(vm::select) ?: vm.reload() }, modifier = Modifier.focusFrame()) { Text("إعادة المحاولة") }
        }
        if (!vm.loading && vm.error == null && vm.titles.isEmpty()) Text("لا توجد حلقات لهذا الأنمي في المصدر حالياً.")
        if (vm.selected == null && vm.titles.isNotEmpty()) Text("اختر العمل المطابق من نتائج المصدر:")
        if (vm.titles.size > 1 || vm.selected == null) {
            // Bounded scrolling keeps large franchises usable on phones and with the TV remote.
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                vm.titles.forEach { title ->
                    TextButton(onClick = { vm.select(title) }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                        Text((if (vm.selected?.id == title.id) "✓ " else "") +
                            (if (title.type == "MOVIE") "فيلم" else "الموسم ${title.season}") + " · ${title.title}")
                    }
                }
            }
        }
        if (vm.selected != null && !vm.loading && vm.error == null && vm.episodes.isEmpty()) Text("لم تتوفر الحلقات بعد.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.episodes.drop(page * 30).take(30).forEach { ep ->
                val watched = history.firstOrNull { it.key == "animeify:${vm.selected?.id}:${ep.number}" }
                OutlinedButton(onClick = { vm.chooseEpisode(ep) }, modifier = Modifier.focusFrame()) {
                    Text((if (watched?.finished == true) "✓ " else if (watched != null) "▶ " else "") +
                        (if (vm.selected?.type == "MOVIE") "مشاهدة الفيلم" else "الحلقة ${ep.number}") + ep.extra.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                }
            }
        }
        if (vm.episodes.size > 30) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { page-- }, enabled = page > 0, modifier = Modifier.focusFrame()) { Text("السابق") }
            Text("${page + 1} / ${(vm.episodes.size + 29) / 30}")
            TextButton(onClick = { page++ }, enabled = (page + 1) * 30 < vm.episodes.size, modifier = Modifier.focusFrame()) { Text("التالي") }
        }
    }
    vm.episode?.let { ep ->
        AlertDialog(
            onDismissRequest = vm::dismissEpisode,
            title = { Text(if (vm.selected?.type == "MOVIE") "الفيلم · اختر السيرفر" else "الحلقة ${ep.number} · اختر السيرفر") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.loadingServers) CircularProgressIndicator()
                    vm.serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!vm.loadingServers && vm.serverError == null && vm.servers.isEmpty()) Text("لا يوجد سيرفر مدعوم لهذه الحلقة حالياً.")
                    vm.servers.forEach { server ->
                        Button(onClick = {
                            val queue = vm.queue(server.key)
                            val index = vm.episodes.indexOfFirst { it.number == ep.number }
                            PlayerActivity.start(context, queue, index)
                            vm.dismissEpisode()
                        }, modifier = Modifier.fillMaxWidth().focusFrame()) { Text(server.label) }
                    }
                    if (vm.serverError != null) TextButton(onClick = { vm.chooseEpisode(ep) }, modifier = Modifier.focusFrame()) { Text("إعادة المحاولة") }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissEpisode, modifier = Modifier.focusFrame()) { Text("إغلاق") } },
        )
    }
}

@Composable
fun AnimeContinueRow(onOpen: (MediaType, Int) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val entries by container.history.entries.collectAsStateWithLifecycle()
    val list = remember(entries) { container.history.continueList().filter { it.animeRef != null }.take(10) }
    if (list.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("تابع مشاهدة الأنمي")
        list.forEach { entry ->
            val ref = entry.animeRef ?: return@forEach
            OutlinedButton(onClick = {
                if (entry.finished) onOpen(MediaType.fromPath(ref.detailType) ?: MediaType.ANIME, ref.detailId)
                else PlayerActivity.start(context, listOf(QueueItem(emptyList(), entry)), 0)
            },
                modifier = Modifier.fillMaxWidth().focusFrame()) { Text("▶ ${entry.title} · ${entry.subtitle.orEmpty()}") }
            TextButton(onClick = { onOpen(MediaType.fromPath(ref.detailType) ?: MediaType.ANIME, ref.detailId) }, modifier = Modifier.focusFrame()) { Text("المواسم والحلقات واختيار السيرفر") }
        }
    }
}
