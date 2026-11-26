package com.alyoushw.watchhub.ui

import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Composable
fun FourULiveChannels() {
    val settings = appContainer().settings
    val accounts by settings.xtreamAccounts.collectAsStateWithLifecycle()
    val active by settings.xtream.collectAsStateWithLifecycle()
    val usable = accounts.filter { it.isComplete }
    var fourU by rememberSaveable { mutableStateOf(!active.isComplete) }
    var prime by rememberSaveable { mutableStateOf(false) }
    // Held sideways the account chips sit beside the search, so the lists keep the height.
    val sideways = isWideShort()
    val chips: @Composable () -> Unit = {
        LazyRow(
            if (sideways) Modifier.widthIn(max = 300.dp) else Modifier,
            contentPadding = PaddingValues(horizontal = if (sideways) 0.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(usable, key = { it.id }) { acc ->
                FilterChip(selected = !prime && !fourU && active.id == acc.id,
                    onClick = { prime = false; fourU = false; settings.setActiveXtream(acc.id) },
                    label = { Text(acc.label) }, modifier = Modifier.focusFrame())
            }
            item { FilterChip(selected = !prime && (fourU || usable.isEmpty()), onClick = { prime = false; fourU = true }, label = { Text("4U") }, modifier = Modifier.focusFrame()) }
            item { FilterChip(selected = prime, onClick = { prime = true }, label = { Text("Prime") }, modifier = Modifier.focusFrame()) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (!sideways || prime) chips()
        Box(Modifier.weight(1f)) {
            if (prime) PrimeLiveChannels() else LiveChannels(showAccounts = false, accountOverride = FourU.account.takeIf { fourU || usable.isEmpty() }, leading = if (sideways) chips else null)
        }
    }
}

/** Opens the matched title first; playback is a separate action on its film/episode page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FourUScreen(lookup: SourceLookup, onBack: () -> Unit) {
    val context = LocalContext.current
    val history by appContainer().history.entries.collectAsStateWithLifecycle()
    val series = lookup.query.type != MediaType.MOVIE
    var loading by remember(lookup) { mutableStateOf(true) }
    var error by remember(lookup) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var matches by remember(lookup) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selected by remember(lookup) { mutableStateOf<JsonObject?>(null) }
    var episodes by remember(selected) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var plot by remember(selected) { mutableStateOf(lookup.overview) }
    var season by rememberSaveable { mutableIntStateOf(1) }
    LaunchedEffect(lookup, retry) {
        loading = true; error = null
        try {
            matches = FourU.matches(series, listOfNotNull(lookup.title, lookup.query.originalTitle, lookup.query.searchTitle), lookup.query.year)
            if (matches.size == 1) selected = matches.single()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "تعذّر تحميل 4U" }
        finally { loading = false }
    }
    LaunchedEffect(selected, retry) {
        val title = selected ?: return@LaunchedEffect
        if (!series) return@LaunchedEffect
        loading = true; error = null
        try {
            val root = FourU.catalog("get_series_info", mapOf("series_id" to title.text4u("series_id"))) as? JsonObject
                ?: throw XtreamException("تعذّر قراءة حلقات 4U")
            episodes = withContext(Dispatchers.Default) {
                val rows = when (val groups = root["episodes"]) {
                    is JsonObject -> groups.values.flatMap { (it as? JsonArray).orEmpty() }
                    is JsonArray -> groups.flatMap { (it as? JsonArray) ?: listOf(it) }
                    else -> emptyList()
                }
                rows.mapNotNull { it as? JsonObject }.filter { it.text4u("id").isNotBlank() }
                    .distinctBy { it.text4u("id") }.sortedWith(compareBy({ it.text4u("season").toIntOrNull() ?: 1 }, { it.text4u("episode_num").toIntOrNull() ?: 0 }))
            }
            season = episodes.firstOrNull()?.text4u("season")?.toIntOrNull() ?: 1
            plot = (root["info"] as? JsonObject)?.text4u("plot")?.takeIf { it.isNotBlank() } ?: lookup.overview
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "تعذّر تحميل حلقات 4U" }
        finally { loading = false }
    }
    fun entry(row: JsonObject, episode: Boolean): WatchEntry {
        val title = selected ?: row
        val id = row.text4u(if (episode) "id" else "stream_id")
        val kind = if (episode) WatchEntry.EPISODE else WatchEntry.MOVIE
        return WatchEntry("fouru:$kind:$id", kind, id, title.text4u("name").ifBlank { lookup.title },
            subtitle = if (episode) "الموسم ${row.text4u("season")} · الحلقة ${row.text4u("episode_num")}" else "4U",
            image = title.text4u(if (series) "cover" else "stream_icon").ifBlank { lookup.poster },
            seriesId = title.text4u("series_id").takeIf { episode },
            tmdbId = lookup.query.tmdbId.takeIf { lookup.query.year > 0 }, accountId = "fouru",
            fourURef = FourURef(if (episode) "series" else "movie", id, row.text4u("container_extension").ifBlank { "mp4" },
                if (lookup.query.year == 0) MediaType.ANIME.path else lookup.query.type.path, lookup.query.tmdbId))
    }
    fun play(row: JsonObject) {
        val queue = if (series) episodes.map { QueueItem(emptyList(), entry(it, true)) } else listOf(QueueItem(emptyList(), entry(row, false)))
        PlayerActivity.start(context, queue, if (series) episodes.indexOf(row).coerceAtLeast(0) else 0)
    }
    BackHandler(enabled = selected != null && matches.size > 1) { selected = null }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text("4U · ${lookup.title}") }, navigationIcon = {
            TextButton(onClick = { if (selected != null && matches.size > 1) selected = null else onBack() }, modifier = Modifier.focusFrame()) { Text("رجوع") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(1200.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item {
                Text(if (selected == null) "جاري البحث عن العمل في 4U…" else "جاري تحميل حلقات 4U…", color = Gold)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            error?.let { message -> item { Text(message); TextButton(onClick = { retry++ }, modifier = Modifier.focusFrame()) { Text("حاول مرة ثانية") } } }
            val title = selected
            if (title == null) {
                if (!loading && error == null && matches.isEmpty()) item { Text("هذا العمل غير موجود في 4U حالياً") }
                items(matches, key = { it.text4u(if (series) "series_id" else "stream_id") }) { row ->
                    OutlinedButton(onClick = { selected = row }, modifier = Modifier.fillMaxWidth().focusFrame()) { Text(row.text4u("name")) }
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val image = title.text4u(if (series) "cover" else "stream_icon").ifBlank { lookup.poster }
                        AsyncImage(model = image, contentDescription = lookup.title, contentScale = ContentScale.Crop, modifier = Modifier.width(110.dp).height(165.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(title.text4u("name"), style = MaterialTheme.typography.titleLarge)
                            if (plot.isNotBlank()) Text(plot, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (!series) item { Button(onClick = { play(title) }, modifier = Modifier.fillMaxWidth().focusFrame()) { Text("▶ تشغيل 4U") } }
                else {
                    val seasons = episodes.map { it.text4u("season").toIntOrNull() ?: 1 }.distinct()
                    item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(seasons) { number -> FilterChip(selected = season == number, onClick = { season = number }, label = { Text("الموسم $number") }, modifier = Modifier.focusFrame()) } } }
                    if (!loading && error == null && episodes.isEmpty()) item { Text("لا توجد حلقات متاحة في 4U حالياً") }
                    items(episodes.filter { (it.text4u("season").toIntOrNull() ?: 1) == season }, key = { it.text4u("id") }) { row ->
                        val watched = history.firstOrNull { it.key == "fouru:episode:${row.text4u("id")}" }
                        EpisodePlaybackRow("الحلقة ${row.text4u("episode_num")}", row.text4u("title"), (row["info"] as? JsonObject)?.text4u("movie_image"), watched,
                            watched != null && !watched.finished, onLongClick = { play(row) }, onClick = { play(row) })
                    }
                }
            }
        }
    }
}
