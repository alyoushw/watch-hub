package com.alyoushw.watchhub.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.data.ApiException
import com.alyoushw.watchhub.data.JAnime
import com.alyoushw.watchhub.data.JCharacterRole
import com.alyoushw.watchhub.data.JReview
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.SearchResult
import com.alyoushw.watchhub.data.StreamingService
import com.alyoushw.watchhub.data.WatchItem
import com.alyoushw.watchhub.data.WatchStatus
import com.alyoushw.watchhub.data.openInService
import com.alyoushw.watchhub.data.openUrl
import com.alyoushw.watchhub.data.searchOn
import kotlinx.coroutines.launch
import java.util.Locale

class AnimeDetailViewModel(private val container: AppContainer, private val id: Int) : ViewModel() {
    var anime by mutableStateOf<JAnime?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var characters by mutableStateOf<List<JCharacterRole>>(emptyList())
        private set
    var reviews by mutableStateOf<List<JReview>>(emptyList())
        private set
    var recommendations by mutableStateOf<List<SearchResult>>(emptyList())
        private set

    private val key = WatchItem.keyOf(MediaType.ANIME, id)

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            error = null
            val main = try {
                container.jikan.anime(id)
            } catch (e: ApiException) {
                error = e.message
                return@launch
            }
            anime = main
            // Keep a saved copy fresh (episode count, streaming links).
            if (container.store.data.value.watchlist.any { it.key == key }) {
                container.store.updateWatch(key) { main.toWatchItem(it) }
            }
            // Extras load one by one after the main page is on screen (Jikan is rate limited).
            try {
                val all = container.jikan.characters(id)
                characters = all.filter { it.role == "Main" }.ifEmpty { all }.take(15)
            } catch (_: ApiException) {
            }
            try {
                reviews = container.jikan.reviews(id).filter { it.review.isNotBlank() }.take(4)
            } catch (_: ApiException) {
            }
            try {
                recommendations = container.jikan.recommendations(id).distinctBy { it.malId }.take(15).map { it.toSearchResult() }
            } catch (_: ApiException) {
            }
        }
    }

    fun addToWatchlist() {
        val a = anime ?: return
        viewModelScope.launch { container.store.upsertWatch(a.toWatchItem(null)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(id: Int, onBack: () -> Unit, onOpen: (MediaType, Int) -> Unit) {
    val container = appContainer()
    val vm: AnimeDetailViewModel = viewModel { AnimeDetailViewModel(container, id) }
    val anime = vm.anime

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(anime?.displayTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                anime != null -> AnimeContent(vm, anime, onOpen)
                vm.error != null -> MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::load)
                else -> CenteredLoading()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeContent(vm: AnimeDetailViewModel, anime: JAnime, onOpen: (MediaType, Int) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by container.store.data.collectAsStateWithLifecycle()
    val saved = data.watchlist.firstOrNull { it.key == WatchItem.keyOf(MediaType.ANIME, anime.malId) }
    val searchTitle = anime.titleEnglish?.ifBlank { null } ?: anime.title

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { AnimeHeader(anime) }

        item { AnimeWatchButtons(anime, searchTitle) }
        item { AnimeSources(anime) }

        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saved == null) {
                    OutlinedButton(onClick = vm::addToWatchlist, modifier = Modifier.fillMaxWidth().focusFrame()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("  أضفه لقائمتي")
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WatchStatus.entries.forEach { status ->
                            FilterChip(
                    modifier = Modifier.focusFrame(),
                                selected = saved.status == status,
                                onClick = { scope.launch { container.store.updateWatch(saved.key) { it.copy(status = status) } } },
                                label = { Text(status.label) },
                                leadingIcon = if (saved.status == status) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp)) }
                                } else null,
                            )
                        }
                    }
                    if (anime.type != "Movie") EpisodeTracker(saved)
                    TextButton(modifier = Modifier.focusFrame(), onClick = { scope.launch { container.store.removeWatch(saved.key) } }) {
                        Text("شيله من قائمتي", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        anime.trailer?.youtubeId?.takeIf { it.isNotBlank() }?.let { youtubeId ->
            item { Trailers(listOf(Trailer(youtubeId, "الإعلان الرسمي"))) { context.openUrl(it.url) } }
        }

        item { AnimeStory(anime) }

        item { AnimeFacts(anime) }

        if (vm.characters.isNotEmpty()) {
            item { CharacterRow(vm.characters) }
        }

        item { AnimeScore(anime) }

        if (vm.reviews.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("آراء المشاهدين (بالإنجليزي)")
                    vm.reviews.forEach { AnimeReviewCard(it) }
                }
            }
        }

        if (vm.recommendations.isNotEmpty()) {
            item {
                TitleRow(
                    title = "إذا عجبك، بيعجبك",
                    items = vm.recommendations,
                    onOpen = { onOpen(MediaType.ANIME, it.id) },
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                anime.url?.let { url ->
                    TextButton(modifier = Modifier.focusFrame(), onClick = { context.openUrl(url) }) { Text("افتح صفحته على MyAnimeList") }
                }
                Text(
                    "البيانات من MyAnimeList عن طريق Jikan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnimeHeader(anime: JAnime) {
    Column {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(anime.images?.best, width = 110.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(anime.displayTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (anime.title != anime.displayTitle) {
                    Text(anime.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                anime.titleJapanese?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    listOfNotNull(
                        "أنمي",
                        typeLabel(anime.type),
                        anime.year?.toString(),
                        anime.episodes?.let { "$it حلقة" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ratingLabel(anime.score)?.let { rating ->
                    Text(
                        "$rating / 10" + (anime.rank?.let { " · المرتبة #$it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                BadgeRow(anime.directLinks().keys.toList())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeWatchButtons(anime: JAnime, searchTitle: String) {
    val context = LocalContext.current
    val known = anime.directLinks()
    // Official links MyAnimeList lists for services we don't know (e.g. other regional platforms).
    val otherLinks = anime.streaming.filter { link -> link.url != null && StreamingService.forUrl(link.url) == null }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        known.forEach { (service, url) ->
            Button(
                onClick = { context.openInService(service, url, searchTitle) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(service.color), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(52.dp).focusFrame(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("شاهد على ${service.label}", fontWeight = FontWeight.Bold)
            }
        }
        otherLinks.forEach { link ->
            OutlinedButton(onClick = { link.url?.let { context.openUrl(it) } }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Text(" شاهد على ${link.name}")
            }
        }
        if (known.isEmpty() && otherLinks.isEmpty()) {
            Text(
                "MyAnimeList ما عنده رابط مشاهدة رسمي لهذا الأنمي. تقدر تدوّر عليه:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CustomAppButtons(searchTitle)

        val missing = StreamingService.entries.filter { it !in known }
        if (missing.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                missing.forEach { service ->
                    OutlinedButton(modifier = Modifier.focusFrame(), onClick = { context.searchOn(service, searchTitle) }) { Text("ابحث في ${service.label}") }
                }
            }
        }
    }
}

@Composable
private fun AnimeStory(anime: JAnime) {
    var showBackground by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("القصة")
        val synopsis = anime.synopsis?.trim().orEmpty()
        if (synopsis.isBlank()) {
            Text("ما فيه وصف لهذا الأنمي للحين.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("الوصف من MyAnimeList بالإنجليزي:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EnglishText(synopsis)
        }
        anime.background?.trim()?.takeIf { it.isNotBlank() }?.let { background ->
            TextButton(modifier = Modifier.focusFrame(), onClick = { showBackground = !showBackground }) {
                Text(if (showBackground) "إخفاء الخلفية" else "خلفية عن العمل")
            }
            if (showBackground) EnglishText(background)
        }
    }
}

@Composable
private fun EnglishText(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
        textAlign = TextAlign.Left,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeFacts(anime: JAnime) {
    val facts = buildList {
        typeLabel(anime.type)?.let { add("النوع" to it) }
        anime.episodes?.let { add("الحلقات" to "$it") }
        anime.duration?.takeIf { it.isNotBlank() && it != "Unknown" }?.let { add("المدة" to it) }
        animeStatus(anime.status)?.let { add("الحالة" to it) }
        anime.aired?.string?.takeIf { it.isNotBlank() }?.let { add("العرض" to it) }
        seasonLabel(anime.season, anime.year)?.let { add("الموسم" to it) }
        anime.studios.map { it.name }.takeIf { it.isNotEmpty() }?.let { add("الاستوديو" to it.joinToString("، ")) }
        anime.source?.takeIf { it.isNotBlank() }?.let { add("المصدر" to it) }
        anime.rating?.takeIf { it.isNotBlank() }?.let { add("التصنيف العمري" to it) }
    }
    val tags = (anime.genres + anime.themes + anime.demographics).map { it.name }.filter { it.isNotBlank() }.distinct()

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("معلومات")
        facts.forEach { (label, value) ->
            Row {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(110.dp),
                )
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { AssistChip(modifier = Modifier.focusFrame(), onClick = {}, label = { Text(it) }) }
            }
        }
    }
}

@Composable
private fun CharacterRow(characters: List<JCharacterRole>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("الشخصيات", Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(characters) { role ->
                Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.width(76.dp).height(104.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        AsyncImage(
                            model = role.character.images?.best,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        role.character.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (role.role == "Main") "رئيسية" else "ثانوية",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimeScore(anime: JAnime) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("تقييم المشاهدين على MyAnimeList")
        val score = anime.score
        if (score == null) {
            Text("ما فيه تقييم للحين.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                String.format(Locale.US, "%.2f", score),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )
            Column {
                Text("من 10", style = MaterialTheme.typography.bodyMedium)
                anime.scoredBy?.let {
                    Text(
                        "حسب " + String.format(Locale.US, "%,d", it) + " مقيّم",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val ranks = listOfNotNull(
            anime.rank?.let { "المرتبة #$it في التقييم" },
            anime.popularity?.let { "#$it في الشعبية" },
            anime.members?.let { String.format(Locale.US, "%,d", it) + " متابع" },
        )
        if (ranks.isNotEmpty()) {
            Text(ranks.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnimeReviewCard(review: JReview) {
    var open by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(review.user?.username.orEmpty(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                review.score?.let { RatingChip(it.toDouble()) }
            }
            EnglishText(review.review.trim(), maxLines = if (open) Int.MAX_VALUE else 4)
            Text(
                if (open) "أقل" else "اقرأ الرأي كامل",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun typeLabel(type: String?): String? = when (type) {
    null, "" -> null
    "TV" -> "مسلسل"
    "Movie" -> "فيلم"
    "OVA" -> "OVA"
    "ONA" -> "ONA (أونلاين)"
    "Special" -> "حلقة خاصة"
    else -> type
}

private fun animeStatus(status: String?): String? = when (status) {
    null, "" -> null
    "Finished Airing" -> "انتهى"
    "Currently Airing" -> "ينعرض الحين"
    "Not yet aired" -> "قريباً"
    else -> status
}

private fun seasonLabel(season: String?, year: Int?): String? {
    val name = when (season) {
        "winter" -> "شتاء"
        "spring" -> "ربيع"
        "summer" -> "صيف"
        "fall" -> "خريف"
        else -> return null
    }
    return listOfNotNull(name, year?.toString()).joinToString(" ")
}
