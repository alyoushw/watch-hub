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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
    // 4U is fast to browse by category (no huge list download), so it offers live, movies and series.
    var mode by rememberSaveable { mutableStateOf("live") }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = mode == "live", onClick = { mode = "live" }, label = { Text("مباشر") }, modifier = Modifier.focusFrame()) }
            item { FilterChip(selected = mode == "movies", onClick = { mode = "movies" }, label = { Text("أفلام 4U") }, modifier = Modifier.focusFrame()) }
            item { FilterChip(selected = mode == "series", onClick = { mode = "series" }, label = { Text("مسلسلات 4U") }, modifier = Modifier.focusFrame()) }
        }
        Box(Modifier.weight(1f)) {
            when (mode) {
                "movies" -> FourUCatalogueBrowse(series = false)
                "series" -> FourUCatalogueBrowse(series = true)
                else -> FourULiveAccounts()
            }
        }
    }
}

@Composable
private fun FourULiveAccounts() {
    val settings = appContainer().settings
    val accounts by settings.xtreamAccounts.collectAsStateWithLifecycle()
    val active by settings.xtream.collectAsStateWithLifecycle()
    val usable = accounts.filter { it.isComplete }
    var fourU by rememberSaveable { mutableStateOf(!active.isComplete) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(usable, key = { it.id }) { acc ->
                FilterChip(selected = !fourU && active.id == acc.id,
                    onClick = { fourU = false; settings.setActiveXtream(acc.id) },
                    label = { Text(acc.label) }, modifier = Modifier.focusFrame())
            }
            item { FilterChip(selected = fourU || usable.isEmpty(), onClick = { fourU = true }, label = { Text("4U") }, modifier = Modifier.focusFrame()) }
        }
        Box(Modifier.weight(1f)) {
            LiveChannels(showAccounts = false, accountOverride = FourU.account.takeIf { fourU || usable.isEmpty() })
        }
    }
}

/** Fast 4U browse: pick a category, its works load on demand (no 11 MB catalogue download). */
@Composable
private fun FourUCatalogueBrowse(series: Boolean) {
    val context = LocalContext.current
    var cats by remember(series) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var cat by remember(series) { mutableStateOf<String?>(null) }
    var items by remember(series) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember(series) { mutableStateOf(true) }
    var error by remember(series) { mutableStateOf<String?>(null) }
    var openSeries by remember(series) { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(series) {
        loading = true; error = null
        try {
            val arr = FourU.catalog(if (series) "get_series_categories" else "get_vod_categories") as? JsonArray ?: JsonArray(emptyList())
            cats = arr.mapNotNull { it as? JsonObject }.map { it.text4u("category_id") to it.text4u("category_name") }.filter { it.first.isNotBlank() }
            cat = cats.firstOrNull()?.first
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "تعذّر تحميل أقسام 4U" }
        finally { loading = false }
    }
    LaunchedEffect(cat, series) {
        val c = cat ?: return@LaunchedEffect
        loading = true; error = null
        try {
            val arr = FourU.catalog(if (series) "get_series" else "get_vod_streams", mapOf("category_id" to c)) as? JsonArray ?: JsonArray(emptyList())
            items = arr.mapNotNull { it as? JsonObject }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "تعذّر تحميل محتوى 4U" }
        finally { loading = false }
    }
    fun playMovie(row: JsonObject) {
        val id = row.text4u("stream_id"); if (id.isBlank()) return
        val entry = WatchEntry("fouru:movie:$id", WatchEntry.MOVIE, id, row.text4u("name"), subtitle = "4U",
            image = row.text4u("stream_icon"), accountId = "fouru",
            fourURef = FourURef("movie", id, row.text4u("container_extension").ifBlank { "mp4" }))
        PlayerActivity.start(context, listOf(QueueItem(emptyList(), entry)), 0)
    }
    openSeries?.let { s -> FourUSeriesEpisodes(s) { openSeries = null }; return }
    Column(Modifier.fillMaxSize()) {
        if (cats.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cats, key = { it.first }) { (id, name) ->
                FilterChip(selected = cat == id, onClick = { cat = id }, label = { Text(name, maxLines = 1) }, modifier = Modifier.focusFrame())
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(12.dp)) }
        LazyColumn(Modifier.fillMaxSize().readableWidth(1200.dp), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!loading && error == null && items.isEmpty()) item { Text("لا يوجد محتوى في هذا القسم حالياً", Modifier.padding(12.dp)) }
            items(items, key = { it.text4u(if (series) "series_id" else "stream_id") }) { row ->
                Row(Modifier.fillMaxWidth().clickable { if (series) openSeries = row else playMovie(row) }.focusFrame().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(model = row.text4u(if (series) "cover" else "stream_icon"), contentDescription = row.text4u("name"),
                        contentScale = ContentScale.Crop, modifier = Modifier.width(60.dp).height(90.dp).clip(RoundedCornerShape(6.dp)))
                    Text(row.text4u("name"), Modifier.weight(1f).align(Alignment.CenterVertically), maxLines = 3)
                }
            }
        }
    }
}

/** 4U series episodes, loaded on demand and played in the app player. */
@Composable
private fun FourUSeriesEpisodes(seriesRow: JsonObject, onBack: () -> Unit) {
    val context = LocalContext.current
    val history by appContainer().history.entries.collectAsStateWithLifecycle()
    var episodes by remember(seriesRow) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember(seriesRow) { mutableStateOf(true) }
    var error by remember(seriesRow) { mutableStateOf<String?>(null) }
    var season by remember(seriesRow) { mutableIntStateOf(1) }
    BackHandler { onBack() }
    LaunchedEffect(seriesRow) {
        loading = true; error = null
        try {
            val root = FourU.catalog("get_series_info", mapOf("series_id" to seriesRow.text4u("series_id"))) as? JsonObject
                ?: throw XtreamException("تعذّر قراءة حلقات 4U")
            episodes = withContext(Dispatchers.Default) {
                val rows = when (val groups = root["episodes"]) {
                    is JsonObject -> groups.values.flatMap { (it as? JsonArray).orEmpty() }
                    is JsonArray -> groups.flatMap { (it as? JsonArray) ?: listOf(it) }
                    else -> emptyList()
                }
                rows.mapNotNull { it as? JsonObject }.filter { it.text4u("id").isNotBlank() }.distinctBy { it.text4u("id") }
                    .sortedWith(compareBy({ it.text4u("season").toIntOrNull() ?: 1 }, { it.text4u("episode_num").toIntOrNull() ?: 0 }))
            }
            season = episodes.firstOrNull()?.text4u("season")?.toIntOrNull() ?: 1
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "تعذّر تحميل الحلقات" }
        finally { loading = false }
    }
    fun entry(ep: JsonObject): WatchEntry {
        val id = ep.text4u("id")
        return WatchEntry("fouru:episode:$id", WatchEntry.EPISODE, id, seriesRow.text4u("name"),
            subtitle = "الموسم ${ep.text4u("season")} · الحلقة ${ep.text4u("episode_num")}",
            image = seriesRow.text4u("cover"), seriesId = seriesRow.text4u("series_id"), accountId = "fouru",
            fourURef = FourURef("series", id, ep.text4u("container_extension").ifBlank { "mp4" }))
    }
    fun play(ep: JsonObject) {
        val inSeason = episodes.filter { (it.text4u("season").toIntOrNull() ?: 1) == season }
        PlayerActivity.start(context, inSeason.map { QueueItem(emptyList(), entry(it)) }, inSeason.indexOf(ep).coerceAtLeast(0))
    }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(seriesRow.text4u("name"), maxLines = 1) }, navigationIcon = {
            TextButton(onClick = onBack, modifier = Modifier.focusFrame()) { Text("رجوع") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(1200.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { m -> item { Text(m) } }
            val seasons = episodes.map { it.text4u("season").toIntOrNull() ?: 1 }.distinct()
            if (seasons.size > 1) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(seasons) { n -> FilterChip(selected = season == n, onClick = { season = n }, label = { Text("الموسم $n") }, modifier = Modifier.focusFrame()) } } }
            if (!loading && error == null && episodes.isEmpty()) item { Text("لا توجد حلقات متاحة في 4U حالياً") }
            items(episodes.filter { (it.text4u("season").toIntOrNull() ?: 1) == season }, key = { it.text4u("id") }) { ep ->
                val watched = history.firstOrNull { it.key == "fouru:episode:${ep.text4u("id")}" }
                EpisodePlaybackRow("الحلقة ${ep.text4u("episode_num")}", ep.text4u("title"), (ep["info"] as? JsonObject)?.text4u("movie_image"), watched,
                    watched != null && !watched.finished, onLongClick = { play(ep) }, onClick = { play(ep) })
            }
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
