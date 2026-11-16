package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Source IDs and published episode numbers, independently of TMDB home recommendations. */
class AnimeUpdatesViewModel(private val api: AnimeifyApi) : ViewModel() {
    var latest by mutableStateOf<List<AnimeifyUpdate>>(emptyList()); private set
    var airing by mutableStateOf<List<AnimeifyUpdate>>(emptyList()); private set
    var latestLoading by mutableStateOf(false); private set
    var airingLoading by mutableStateOf(false); private set
    var latestMoreLoading by mutableStateOf(false); private set
    var airingMoreLoading by mutableStateOf(false); private set
    var latestError by mutableStateOf<String?>(null); private set
    var airingError by mutableStateOf<String?>(null); private set
    var latestHasMore by mutableStateOf(false); private set
    var airingHasMore by mutableStateOf(false); private set
    private var latestFrom = 0
    private var airingFrom = 0
    private var refreshedAt = 0L
    private var refreshJob: Job? = null
    private var moreJob: Job? = null

    fun refresh(force: Boolean = false) {
        if (refreshJob?.isActive == true) return
        if (!force && System.currentTimeMillis() - refreshedAt < 5 * 60_000L) return
        refreshJob?.cancel()
        moreJob?.cancel()
        latestMoreLoading = false
        airingMoreLoading = false
        refreshedAt = System.currentTimeMillis()
        latestLoading = true
        airingLoading = true
        latestError = null
        airingError = null
        refreshJob = viewModelScope.launch {
            supervisorScope {
                val episodes = async { api.latestEpisodes() }
                val season = async { api.currentSeason() }
                try {
                    val page = episodes.await()
                    latest = page.items.distinctBy { "${it.title.id}:${it.episode.number}" }
                    latestFrom = page.nextFrom
                    latestHasMore = page.hasMore
                } catch (e: ApiException) { latestError = e.message }
                finally { latestLoading = false }
                try {
                    val page = season.await()
                    val titles = (latest.map { it.title }.filter { it.status == "AIRING" } + page.items).distinctBy { it.id }
                    val result = publishedAiring(titles)
                    airing = result.first
                    airingError = if (result.second) "تعذّر قراءة حلقات بعض الأعمال؛ أعد التحديث للمحاولة" else null
                    airingFrom = page.nextFrom
                    airingHasMore = page.hasMore
                } catch (e: ApiException) { airingError = e.message }
                finally { airingLoading = false }
            }
        }
    }

    fun loadMore(currentlyAiring: Boolean) {
        if (refreshJob?.isActive == true || moreJob?.isActive == true) return
        if (currentlyAiring && !airingHasMore || !currentlyAiring && !latestHasMore) return
        if (currentlyAiring) { airingMoreLoading = true; airingError = null }
        else { latestMoreLoading = true; latestError = null }
        moreJob = viewModelScope.launch {
            try {
                if (currentlyAiring) {
                    val page = api.currentSeason(airingFrom)
                    val result = publishedAiring(page.items.filter { title -> airing.none { it.title.id == title.id } })
                    airing = (airing + result.first).distinctBy { it.title.id }
                    airingFrom = page.nextFrom
                    airingHasMore = page.hasMore
                    if (result.second) airingError = "تعذّر قراءة حلقات بعض الأعمال؛ أعد التحديث للمحاولة"
                } else {
                    val page = api.latestEpisodes(latestFrom)
                    latest = (latest + page.items).distinctBy { "${it.title.id}:${it.episode.number}" }
                    latestFrom = page.nextFrom
                    latestHasMore = page.hasMore
                }
            } catch (e: ApiException) {
                if (currentlyAiring) airingError = e.message else latestError = e.message
            } finally { airingMoreLoading = false; latestMoreLoading = false }
        }
    }

    private suspend fun publishedAiring(titles: List<AnimeifyTitle>): Pair<List<AnimeifyUpdate>, Boolean> = coroutineScope {
        val gate = Semaphore(4)
        val results = titles.filter { it.status == "AIRING" && it.type !in setOf("MOVIE", "CARTOON") }.map { title -> async {
            gate.withPermit {
                try {
                    // Catalogue Episodes can be a planned total; only the actual episode list proves publication.
                    api.episodes(title.id).lastOrNull()?.let { AnimeifyUpdate(title, it) } to false
                } catch (_: ApiException) { null to true }
            }
        } }.awaitAll()
        results.mapNotNull { it.first } to results.any { it.second }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeUpdatesRow(vm: AnimeUpdatesViewModel, currentlyAiring: Boolean, onOpen: (AnimeLookup) -> Unit,
    onOpenDetails: (MediaType, Int) -> Unit) {
    val title = if (currentlyAiring) "أنميات تُعرض الآن" else "آخر الحلقات"
    val data = if (currentlyAiring) vm.airing else vm.latest
    val loading = if (currentlyAiring) vm.airingLoading else vm.latestLoading
    val error = if (currentlyAiring) vm.airingError else vm.latestError
    val moreLoading = if (currentlyAiring) vm.airingMoreLoading else vm.latestMoreLoading
    val hasMore = if (currentlyAiring) vm.airingHasMore else vm.latestHasMore
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AnimeifyUpdate?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    fun choose(update: AnimeifyUpdate, details: Boolean) { showDetails = details; selected = update }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle(title)
                Text(if (currentlyAiring) "أعمال جارية من أنمي فاي · رقم آخر حلقة متاحة" else "آخر تحديثات أنمي فاي · أرقام الحلقات المنشورة",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { expanded = true }, modifier = Modifier.focusFrame()) { Text("عرض الكل") }
        }
        if (loading && data.isEmpty()) CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp), color = Gold)
        if (error != null) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { vm.refresh(true) }, modifier = Modifier.focusFrame()) { Text("إعادة المحاولة") }
        }
        if (!loading && error == null && data.isEmpty()) Text("لا توجد حلقات متاحة في النتائج الحالية", Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data.take(20), key = { "${it.title.id}:${it.episode.number}" }) { update ->
                AnimeUpdateCard(update, currentlyAiring, Modifier.width(if (isTv()) 176.dp else 148.dp),
                    onClick = { choose(update, false) }, onLongClick = { choose(update, true) })
            }
        }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = {
                IconButton(onClick = { expanded = false }, modifier = Modifier.focusFrame()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                }
            }, actions = { TextButton(onClick = { vm.refresh(true) }, modifier = Modifier.focusFrame()) { Text("تحديث") } })
        }) { padding ->
            LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(data, key = { "${it.title.id}:${it.episode.number}" }) { update ->
                    AnimeUpdateCard(update, currentlyAiring, Modifier.fillMaxWidth(),
                        onClick = { choose(update, false) }, onLongClick = { choose(update, true) })
                }
                item(key = "load-more", span = { GridItemSpan(maxLineSpan) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (loading || moreLoading) CircularProgressIndicator(Modifier.size(28.dp), color = Gold)
                        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                        if (hasMore) OutlinedButton(onClick = { vm.loadMore(currentlyAiring) }, enabled = !loading && !moreLoading,
                            modifier = Modifier.focusFrame()) { Text("تحميل المزيد") }
                        else if (!loading && data.isEmpty()) Text("لا توجد حلقات متاحة في النتائج الحالية")
                    }
                }
            }
        }
    }
    selected?.let { update ->
        val anime = update.title
        fun openSource(play: Boolean) {
            selected = null
            expanded = false
            onOpen(AnimeLookup(anime.title, anime.title, anime.type == "MOVIE", anime.poster,
                MediaType.ANIME, anime.malId ?: 0, anime.malId, sourceTitle = anime,
                initialEpisode = if (play) update.episode.number else null))
        }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(anime.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showDetails) {
                    val malId = anime.malId?.takeIf { it > 0 }
                    Button(onClick = {
                        if (malId != null) { selected = null; expanded = false; onOpenDetails(MediaType.ANIME, malId) }
                    }, enabled = malId != null, modifier = Modifier.fillMaxWidth().focusFrame()) { Text("معلومات الأنمي في Watch Hub") }
                    if (malId == null) Text("صفحة معلومات هذا الأنمي غير متاحة حالياً", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(onClick = { openSource(true) }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                        Text("تشغيل الحلقة ${update.episode.number}")
                    }
                }
                OutlinedButton(onClick = { openSource(false) }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                    Text("صفحة الأنمي في أنمي فاي")
                }
            }
        }, confirmButton = {}, dismissButton = {
            TextButton(onClick = { selected = null }, modifier = Modifier.focusFrame()) { Text("إغلاق") }
        })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnimeUpdateCard(update: AnimeifyUpdate, airing: Boolean, modifier: Modifier,
    onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val anime = update.title
    Column(modifier.focusFrame(shape).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
        .remoteLongPress(onLongPress = onLongClick, onClick = onClick)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            AsyncImage(anime.poster, contentDescription = anime.title, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
            Text(if (airing) "مستمر" else "الحلقة ${update.episode.number}", color = if (airing) Color.White else Color(0xFF1C1206),
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(if (airing) Color(0xFF477838) else Gold, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            Text(anime.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
        Text(if (airing) "آخر حلقة: ${update.episode.number}" else "الحلقة ${update.episode.number} · أنمي فاي",
            color = Gold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(10.dp))
    }
}
