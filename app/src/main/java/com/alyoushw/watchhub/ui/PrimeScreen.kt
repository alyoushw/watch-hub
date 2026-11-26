package com.alyoushw.watchhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.*
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.QueueItem
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrimeScreen(lookup: SourceLookup, onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val history by container.history.entries.collectAsStateWithLifecycle()
    var query by remember(lookup) { mutableStateOf(lookup.query.takeIf { it.year > 0 && it.type != MediaType.ANIME }) }
    var candidates by remember(lookup) { mutableStateOf<List<SearchResult>>(emptyList()) }
    var seasons by remember(lookup) { mutableStateOf<List<SourceSeason>>(emptyList()) }
    var season by rememberSaveable(lookup) { mutableIntStateOf(lookup.query.season ?: 1) }
    var episodes by remember(lookup) { mutableStateOf<List<AiredEpisodeDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(lookup, retry) {
        if (query == null) {
            loading = true; error = null
            try {
                candidates = container.api.search(lookup.query.originalTitle.ifBlank { lookup.title })
                    .filter { it.mediaType in listOf("movie", "tv") }
                if (candidates.size == 1) {
                    val row = candidates.single()
                    query = lookup.query.copy(type = if (row.mediaType == "movie") MediaType.MOVIE else MediaType.TV,
                        tmdbId = row.id, year = (row.releaseDate ?: row.firstAirDate)?.take(4)?.toIntOrNull() ?: 0)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "تعذّر تحديد العمل في Prime؛ حاول مرة ثانية" }
            finally { loading = false }
        }
    }
    LaunchedEffect(query, season, retry) {
        val q = query ?: return@LaunchedEffect
        if (q.type == MediaType.MOVIE) return@LaunchedEffect
        loading = true; error = null; episodes = emptyList()
        try {
            if (seasons.isEmpty()) seasons = container.api.details(MediaType.TV, q.tmdbId).seasons
                .filter { it.seasonNumber > 0 }.map { SourceSeason(it.seasonNumber, it.episodeCount) }
            episodes = container.api.seasonEpisodes(q.tmdbId, season).filter { it.episodeNumber > 0 }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "تعذّر تحميل حلقات Prime؛ حاول مرة ثانية" }
        finally { loading = false }
    }
    fun watchEntry(q: SourceQuery): WatchEntry {
        val series = q.type != MediaType.MOVIE
        val id = if (series) "${q.tmdbId}:${q.season}:${q.episode}" else q.tmdbId.toString()
        return WatchEntry("prime:${if (series) "episode" else "movie"}:$id",
            if (series) WatchEntry.EPISODE else WatchEntry.MOVIE, id, lookup.title,
            subtitle = if (series) "الموسم ${q.season} · الحلقة ${q.episode}" else "Prime",
            image = lookup.poster, seriesId = q.tmdbId.toString().takeIf { series }, accountId = "prime", tmdbId = q.tmdbId,
            primeRef = PrimeRef(query = q))
    }
    fun play(number: Int? = null) {
        val q = query ?: return
        val queue = if (q.type == MediaType.MOVIE) listOf(QueueItem(emptyList(), watchEntry(q.copy(season = null, episode = null))))
        else episodes.map { QueueItem(emptyList(), watchEntry(q.copy(season = season, episode = it.episodeNumber))) }
        val index = if (number == null) 0 else episodes.indexOfFirst { it.episodeNumber == number }.coerceAtLeast(0)
        PlayerActivity.start(context, queue, index)
    }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { TopAppBar(title = { Text("Prime · ${lookup.title}") }, navigationIcon = {
        TextButton(onClick = onBack, modifier = Modifier.focusFrame()) { Text("رجوع") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(1200.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                lookup.poster?.let { AsyncImage(it, lookup.title, contentScale = ContentScale.Crop, modifier = Modifier.width(110.dp).height(165.dp)) }
                Column(Modifier.weight(1f)) { Text(lookup.title, style = MaterialTheme.typography.titleLarge); Text(lookup.overview) }
            } }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { Text(message); TextButton(onClick = { retry++ }, modifier = Modifier.focusFrame()) { Text("حاول مرة ثانية") } } }
            if (query == null) {
                if (!loading && candidates.isEmpty() && error == null) item { Text("لم نعثر على هذا العمل") }
                items(candidates, key = { "${it.mediaType}:${it.id}" }) { row ->
                    OutlinedButton(onClick = {
                        seasons = emptyList(); season = 1
                        query = lookup.query.copy(type = if (row.mediaType == "movie") MediaType.MOVIE else MediaType.TV,
                            tmdbId = row.id, year = (row.releaseDate ?: row.firstAirDate)?.take(4)?.toIntOrNull() ?: 0)
                    }, modifier = Modifier.fillMaxWidth().focusFrame()) { Text(row.title ?: row.name.orEmpty()) }
                }
            } else if (query?.type == MediaType.MOVIE) {
                item { Button(onClick = { play() }, modifier = Modifier.fillMaxWidth().focusFrame()) { Text("▶ تشغيل Prime") } }
            } else {
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons.filter { it.number > 0 }, key = { it.number }) { row ->
                        FilterChip(selected = season == row.number, onClick = { season = row.number }, label = { Text("الموسم ${row.number}") }, modifier = Modifier.focusFrame())
                    }
                } }
                if (!loading && error == null && episodes.isEmpty()) item { Text("لا توجد حلقات لهذا الموسم حالياً") }
                items(episodes, key = { it.episodeNumber }) { row ->
                    val q = query ?: return@items
                    val entry = watchEntry(q.copy(season = season, episode = row.episodeNumber))
                    val watched = history.firstOrNull { it.key == entry.key }
                    EpisodePlaybackRow("الحلقة ${row.episodeNumber}", row.name.orEmpty(), null, watched,
                        watched != null && !watched.finished, onLongClick = { play(row.episodeNumber) }, onClick = { play(row.episodeNumber) })
                }
            }
        }
    }
}

@Composable
fun PrimeLiveChannels() {
    val context = LocalContext.current
    var channels by remember { mutableStateOf<List<PrimeChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(retry) {
        loading = true; error = null
        try { channels = Prime.channels() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "تعذّر تحميل Prime؛ تحقق من الاتصال وحاول مرة ثانية" }
        finally { loading = false }
    }
    val visible = remember(channels, search, group) { channels.filter { (group.isBlank() || it.group == group) && it.name.contains(search, true) } }
    fun entry(row: PrimeChannel) = WatchEntry("prime:live:${row.id}", WatchEntry.LIVE, row.id, row.name,
        image = row.image, subtitle = "Prime · ${row.group}", accountId = "prime", primeRef = PrimeRef(channelId = row.id))
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (loading) { Text("جاري تحميل Prime… التهيئة الأولى قد تستغرق وقتاً", color = Gold); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { Text(it); TextButton(onClick = { retry++ }, modifier = Modifier.focusFrame()) { Text("حاول مرة ثانية") } }
        OutlinedTextField(search, { search = it }, label = { Text("ابحث عن قناة في Prime") }, singleLine = true, modifier = Modifier.fillMaxWidth().tvTextField())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(group.isEmpty(), { group = "" }, label = { Text("الكل") }, modifier = Modifier.focusFrame()) }
            items(channels.map { it.group }.distinct(), key = { it }) { name ->
                FilterChip(group == name, { group = name }, label = { Text(name) }, modifier = Modifier.focusFrame())
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!loading && error == null && visible.isEmpty()) item { Text("لا توجد قنوات مطابقة") }
            items(visible, key = { it.id }) { row ->
                OutlinedButton(onClick = { PlayerActivity.start(context, visible.map { QueueItem(emptyList(), entry(it)) }, visible.indexOf(row)) }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                    row.image?.let { AsyncImage(it, null, modifier = Modifier.size(40.dp)); Spacer(Modifier.width(12.dp)) }; Text(row.name)
                }
            }
        }
    }
}
