package com.alyoushw.watchhub.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.*
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.QueueItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class SourceSeason(val number: Int, val episodes: Int, val year: Int? = null)
@Serializable data class SourceLookup(
    val query: SourceQuery, val title: String, val overview: String = "", val overviewIsEnglish: Boolean = false,
    val poster: String? = null, val backdrop: String? = null, val seasons: List<SourceSeason> = emptyList(),
    val providerId: String? = null,
)
private val sourceNavigationJson = Json { ignoreUnknownKeys = true }
fun SourceLookup.route(): String = "watch-sources/" + Uri.encode(sourceNavigationJson.encodeToString(SourceLookup.serializer(), this))
fun decodeSourceLookup(value: String): SourceLookup = sourceNavigationJson.decodeFromString(SourceLookup.serializer(), value)
fun DetailInfo.sourceLookup(): SourceLookup {
    val season = seasonDetails.firstOrNull { it.number > 0 }?.number ?: seasonDetails.firstOrNull()?.number ?: 1
    return SourceLookup(SourceQuery(type, id, originalTitle.ifBlank { searchTitle }, year?.toIntOrNull() ?: 0,
        season.takeIf { type == MediaType.TV }, 1.takeIf { type == MediaType.TV }, imdbId, searchTitle, localizedTitle = title), title, overview, overviewIsEnglish,
        posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }, backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
        seasonDetails.map { SourceSeason(it.number, it.episodes, it.year?.toIntOrNull()) })
}

@Composable
fun SourceWatchButton(lookup: SourceLookup, onOpen: (SourceLookup) -> Unit, modifier: Modifier = Modifier, label: String = "تشغيل تورنت") {
    Button(onClick = { onOpen(lookup) },
        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206)),
        modifier = modifier.height(44.dp).focusFrame()) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.Bold)
    }
}

/** Same episode-card/seasons/dialog flow as Animeify; uses the existing player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceWatchScreen(
    lookup: SourceLookup, onBack: () -> Unit,
    sources: WatchSources? = null,
    onPlay: ((WatchSourceRef) -> Unit)? = null,
) {
    val container = appContainer()
    val context = LocalContext.current
    val website = when (lookup.providerId) { "moviz-time" -> "Moviz Time"; "topcinema" -> "TopCinema"; "egybest" -> "EgyBest"; "akwam" -> "أكوام"; else -> null }
    val repository = sources ?: when (lookup.providerId) {
        "moviz-time" -> container.movizTimeSources
        "topcinema" -> container.topCinemaSources
        "egybest" -> container.egyBestSources
        "akwam" -> container.akwamSources
        else -> container.watchSources
    }
    val catalogue by container.settings.sourceCatalogue.collectAsStateWithLifecycle()
    val indexed by container.settings.torrentIndexEnabled.collectAsStateWithLifecycle()
    val history by container.history.entries.collectAsStateWithLifecycle()
    var season by rememberSaveable(lookup.query.tmdbId) { mutableIntStateOf(lookup.query.season ?: 1) }
    var number by rememberSaveable(lookup.query.tmdbId, season) { mutableIntStateOf(1) }
    var chooseSource by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val series = lookup.query.type == MediaType.TV
    val query = lookup.query.copy(season = season.takeIf { series }, episode = number.takeIf { series },
        seasonYear = lookup.seasons.firstOrNull { it.number == season }?.year.takeIf { series })
    var loading by remember(query, catalogue, indexed, retry) { mutableStateOf(query.valid) }
    var result by remember(query, catalogue, indexed, retry) { mutableStateOf<SourceSearch?>(null) }
    var openedLink by remember(query) { mutableStateOf<SourceWebLink?>(null) }
    var showWebsiteDetails by remember(query) { mutableStateOf(false) }
    LaunchedEffect(query, catalogue, indexed, retry) {
        result = null
        if (!query.valid) return@LaunchedEffect
        loading = true
        try { result = repository.search(query) }
        finally { loading = false }
    }
    openedLink?.let { link ->
        androidx.activity.compose.BackHandler { openedLink = null }
        KoraScreen(onBack = { openedLink = null }, homeUrl = link.url, pageTitle = link.label)
        return
    }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(lookup.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.focusFrame()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            } }, actions = { TextButton(onClick = { retry++ }, modifier = Modifier.focusFrame()) { Text("إعادة البحث") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(1200.dp).testTag("source-episodes"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "story") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    lookup.poster?.let { AsyncImage(model = it, contentDescription = lookup.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(8.dp))) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (lookup.overview.isNotBlank()) TranslatedText(lookup.overview, isEnglish = lookup.overviewIsEnglish,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text((if (series) "الموسم $season" else "فيلم") + (website?.let { " · $it" } ?: " · تورنت"), color = Gold, style = MaterialTheme.typography.labelMedium)
                        Text(if (lookup.providerId in setOf("egybest", "akwam")) "تظهر روابط المشاهدة والتحميل كما يعلنها الموقع. الروابط المتوافقة تُشغّل بالمشغّل الرئيسي، والبقية تفتح بمشغّل الموقع داخل التطبيق."
                            else if (website != null) "اختر الفيلم أو الحلقة لعرض سيرفرات $website المتوافقة. تُجلب روابط التشغيل من الموقع عند كل مشاهدة."
                            else "يبدأ التشغيل بعد تجهيز الأجزاء الأولى ويستمر التنزيل أثناء المشاهدة. سرعة البداية تعتمد على الأقران والاتصال.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (series && lookup.seasons.isNotEmpty()) item(key = "seasons") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lookup.seasons, key = { it.number }) { item ->
                        FilterChip(selected = season == item.number, onClick = { season = item.number; chooseSource = false },
                            label = { Text("الموسم ${item.number}") }, modifier = Modifier.focusFrame())
                    }
                }
            }
            if (loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!query.valid) item { Text("بيانات مطابقة العمل غير مكتملة.") }
            val count = if (series) lookup.seasons.firstOrNull { it.number == season }?.episodes ?: 1 else 1
            if (count == 0) item { Text("لم تتوفر حلقات هذا الموسم بعد.") }
            items(count.coerceIn(0, 2000), key = { "episode-$season-$it" }) { index ->
                val episode = index + 1
                val episodeQuery = query.copy(episode = episode.takeIf { series })
                val key = sourceKey(episodeQuery)
                val watched = history.firstOrNull { it.key == key }
                EpisodePlaybackRow(label = if (series) "الحلقة $episode" else "الفيلم",
                    title = if (series) "${lookup.title} S${season.toString().padStart(2, '0')} E${episode.toString().padStart(2, '0')}" else lookup.title,
                    image = null, watched = watched, current = watched != null && !watched.finished,
                    onLongClick = { number = episode; chooseSource = true },
                    onClick = { number = episode; chooseSource = true })
            }
        }
    }
    if (chooseSource) AlertDialog(onDismissRequest = { chooseSource = false },
        title = { Text((if (series) "الحلقة $number" else "الفيلم") + " · اختر المصدر والجودة") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) CircularProgressIndicator()
                result?.sources?.forEach { source ->
                    Text(source.providerName + " · " + sourceStatus(source), color = Gold)
                    source.variants.forEach { variant ->
                        Button(onClick = {
                            val ref = WatchSourceRef(source.providerId, source.id, query, variant.id, source.providerName)
                            if (onPlay != null) onPlay(ref)
                            else PlayerActivity.start(context, listOf(QueueItem(emptyList(), sourceEntry(lookup, ref))), 0)
                            chooseSource = false
                        }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                            Text(if (source.providerId == "akwam") "تشغيل · " + variant.label
                                else source.providerName + " · " + sourceQualityLabel(variant.label))
                        }
                        if (source.providerId != "akwam") {
                            Text(variant.label, style = MaterialTheme.typography.bodySmall)
                            Text(source.attribution, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (source.providerId == "akwam" && source.variants.isNotEmpty()) Text(source.attribution, style = MaterialTheme.typography.labelSmall)
                    if (source.providerId == "akwam" && (source.webLinks.isNotEmpty() || source.issues.isNotEmpty())) {
                        TextButton(onClick = { showWebsiteDetails = !showWebsiteDetails }, modifier = Modifier.focusFrame()) {
                            Text(if (showWebsiteDetails) "إخفاء تفاصيل الموقع" else "تفاصيل الخوادم وصفحات الموقع · فتح يدوي")
                        }
                    }
                    if ((source.providerId != "akwam" || showWebsiteDetails) && source.webLinks.isNotEmpty()) {
                        (if (source.providerId == "akwam") listOf(true, false) else listOf(false, true)).forEach { downloading ->
                            val pages = source.webLinks.filter { it.download == downloading }
                            if (pages.isNotEmpty()) {
                                Text(if (downloading && source.providerId == "akwam") "جميع سيرفرات الموقع · اختر وجرّب"
                                    else if (downloading) "صفحات التحميل الأصلية" else "جميع سيرفرات المشاهدة وصفحات الموقع · اختر وجرّب")
                                pages.forEach { link ->
                                    OutlinedButton(onClick = { openedLink = link; chooseSource = false },
                                        modifier = Modifier.fillMaxWidth().focusFrame()) {
                                        Text((if (link.download) "صفحة تحميل · " else "فتح الصفحة · ") + link.label)
                                    }
                                }
                            }
                        }
                    }
                    source.issues.distinct().filter { source.providerId != "akwam" || showWebsiteDetails }.forEach { issue ->
                        Text(issue.server + " — " + sourceIssueLabel(issue), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                result?.errors?.firstOrNull()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                result?.availability?.forEach { known ->
                    Text(known.message, color = Gold)
                    known.episodes.keys.sorted().filter { it != season && lookup.seasons.any { item -> item.number == it } }.forEach { availableSeason ->
                        TextButton(onClick = { season = availableSeason; chooseSource = false }, modifier = Modifier.focusFrame()) {
                            Text("عرض الموسم $availableSeason الموجود في الموقع")
                        }
                    }
                    known.episodes[season]?.let { episodes ->
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            episodes.sorted().forEach { availableEpisode ->
                                TextButton(onClick = { if (number == availableEpisode) retry++ else number = availableEpisode },
                                    modifier = Modifier.focusFrame()) { Text("الحلقة $availableEpisode") }
                            }
                        }
                    }
                }
                if (!loading && result?.sources?.isEmpty() == true && result?.availability?.isEmpty() == true) {
                    Text(if (result?.errors?.isNotEmpty() == true) "تعذّر البحث أو التحقق في الموقع؛ لا يمكن الحكم على توفر العمل الآن."
                        else "لم يعثر البحث على نتيجة مؤكدة المطابقة. قد يختلف الاسم أو تكون نتائج الموقع محدودة؛ هذا لا يثبت حذف العمل.")
                }
                if (!loading && website != null) sourceSiteSearch(lookup, query)?.let { url ->
                    TextButton(onClick = { openedLink = SourceWebLink("البحث في $website", url); chooseSource = false },
                        modifier = Modifier.fillMaxWidth().focusFrame()) { Text("تحقق بنفسك في بحث $website") }
                }
                if (!loading && (result?.sources?.isEmpty() == true || result?.errors?.isNotEmpty() == true))
                    TextButton(onClick = { retry++ }, modifier = Modifier.focusFrame()) { Text("إعادة البحث") }
            }
        }, confirmButton = { TextButton(onClick = { chooseSource = false }, modifier = Modifier.focusFrame()) { Text("إغلاق") } })
}

private fun sourceStatus(source: WatchSource): String = when {
    !source.matchVerified -> "نتيجة محتملة؛ لم تُؤكّد هوية العمل"
    source.variants.isNotEmpty() && source.variants.all { it.transport in setOf(SourceTransport.MAGNET, SourceTransport.TORRENT) } -> "مصدر تورنت مفهرس؛ تُفحص جاهزيته عند التشغيل"
    source.variants.isNotEmpty() -> "العمل موجود · ${source.variants.count { it.transport !in setOf(SourceTransport.MAGNET, SourceTransport.TORRENT) }} رابط اجتاز فحص البداية"
    source.issues.isNotEmpty() && source.issues.all { it.health == SourceHealth.UNAVAILABLE } -> "العمل موجود، لكن الروابط المفحوصة غير متاحة حالياً"
    source.issues.any { it.health in setOf(SourceHealth.BLOCKED, SourceHealth.NETWORK_ERROR) } -> "العمل موجود، لكن تعذّر التحقق بسبب الاتصال أو الحماية"
    else -> "العمل موجود؛ لم يتوفر رابط مباشر مؤكّد للمشغّل الرئيسي"
}

private fun sourceIssueLabel(issue: SourceIssue): String = when (issue.health) {
    SourceHealth.PLAYABLE -> "اجتاز فحص بداية الفيديو"
    SourceHealth.UNAVAILABLE -> "الرابط غير متاح حالياً" + (issue.httpStatus?.let { " ($it)" } ?: "")
    SourceHealth.BLOCKED -> "الوصول محجوب أو يتطلب تفاعلاً في الموقع" + (issue.httpStatus?.let { " ($it)" } ?: "")
    SourceHealth.NETWORK_ERROR -> "تعذّر الاتصال أو انتهت المهلة؛ حالة الفيديو غير معروفة"
    SourceHealth.UNSUPPORTED -> "ليس رابط فيديو متوافقاً؛ قد يحتاج مشغّل الموقع"
    SourceHealth.UNVERIFIED -> "لم نتمكن من تأكيد رابط فيديو؛ لا يعني ذلك أن العمل غير موجود"
}

private fun sourceSiteSearch(lookup: SourceLookup, query: SourceQuery): String? {
    val title = query.websiteTitles().firstOrNull() ?: return null
    val base = when (lookup.providerId) {
        "egybest" -> "https://egytbest.live/"
        "moviz-time" -> "https://moviz-time.buzz/"
        "topcinema" -> "https://web6.topcinema.fan/search/"
        "akwam" -> "https://akw.cam/search"
        else -> return null
    }
    val param = when (lookup.providerId) { "topcinema" -> "query"; "akwam" -> "q"; else -> "s" }
    return Uri.parse(base).buildUpon().appendQueryParameter(param, title).build().toString()
}

internal fun sourceQualityLabel(label: String): String = when {
    Regex("(?i)(2160|\\b4k\\b|\\buhd\\b)").containsMatchIn(label) -> "4K"
    "1080" in label -> "FHD · 1080p"
    "720" in label -> "HD · 720p"
    "480" in label -> "SD · 480p"
    "360" in label -> "SD · 360p"
    else -> label.takeIf { it.length < 32 } ?: "الجودة المتاحة"
}
internal fun sourceKey(query: SourceQuery) = "source:${query.type.path}:${query.tmdbId}:${query.season ?: 0}:${query.episode ?: 0}"
internal fun sourceEntry(lookup: SourceLookup, ref: WatchSourceRef): WatchEntry = WatchEntry(
    key = sourceKey(ref.query), kind = if (ref.query.type == MediaType.TV) WatchEntry.EPISODE else WatchEntry.MOVIE,
    itemId = ref.query.episode?.toString() ?: ref.query.tmdbId.toString(), title = lookup.title,
    subtitle = if (ref.query.type == MediaType.TV) "الموسم ${ref.query.season} · الحلقة ${ref.query.episode}" else ref.query.year.toString(),
    image = lookup.backdrop ?: lookup.poster, seriesId = ref.query.tmdbId.toString().takeIf { ref.query.type == MediaType.TV },
    accountId = "watch-sources", tmdbId = ref.query.tmdbId, sourceRef = ref,
)

@Composable
fun SourceSettingsSection() {
    val settings = appContainer().settings
    val saved by settings.sourceCatalogue.collectAsStateWithLifecycle()
    val indexed by settings.torrentIndexEnabled.collectAsStateWithLifecycle()
    var url by remember(saved) { mutableStateOf(saved) }
    var message by remember { mutableStateOf<String?>(null) }
    SectionTitle("مصادر المشاهدة")
    Text("ابحث عن العمل ثم افتح «تشغيل تورنت» للمواسم والحلقات واختيار المصدر. البحث في Internet Archive للأفلام تلقائي؛ الكتالوج الإضافي اختياري.", style = MaterialTheme.typography.bodySmall)
    FilterChip(selected = indexed, onClick = { settings.setTorrentIndexEnabled(!indexed) },
        label = { Text("البحث التلقائي في The Pirate Bay") }, modifier = Modifier.focusFrame())
    OutlinedTextField(url, { url = it; message = null }, label = { Text("كتالوج إضافي اختياري") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard())
    Button(onClick = { settings.setSourceCatalogue(url); message = "حُفظ الكتالوج" }, modifier = Modifier.focusFrame()) { Text("حفظ المصادر") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
