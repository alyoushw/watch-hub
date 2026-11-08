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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontStyle
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
import com.alyoushw.watchhub.data.Details
import com.alyoushw.watchhub.data.EpisodeMark
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.ProviderDto
import com.alyoushw.watchhub.data.RegionProviders
import com.alyoushw.watchhub.data.SearchResult
import com.alyoushw.watchhub.data.SeasonInfo
import com.alyoushw.watchhub.data.StreamingService
import com.alyoushw.watchhub.data.TmdbApi
import com.alyoushw.watchhub.data.ApiException
import com.alyoushw.watchhub.data.ArabicTranslator
import com.alyoushw.watchhub.data.countryFlag
import com.alyoushw.watchhub.data.countryLabel
import com.alyoushw.watchhub.data.WatchItem
import com.alyoushw.watchhub.data.WatchStatus
import com.alyoushw.watchhub.data.appLabel
import com.alyoushw.watchhub.data.openCustomApp
import com.alyoushw.watchhub.data.openProviderApp
import com.alyoushw.watchhub.data.openUrl
import com.alyoushw.watchhub.data.watchOn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

data class Trailer(val key: String, val name: String) {
    val url: String get() = "https://www.youtube.com/watch?v=$key"
    val thumbnail: String get() = "https://img.youtube.com/vi/$key/hqdefault.jpg"
}

data class SeasonDetail(
    val number: Int,
    val name: String,
    val episodes: Int,
    val year: String?,
    val overview: String,
    val posterPath: String?,
)

data class CastMember(val id: Int, val name: String, val character: String?, val profilePath: String?)

data class ReviewInfo(val author: String, val rating: Double?, val content: String, val date: String?)

data class DetailInfo(
    val type: MediaType,
    val id: Int,
    val title: String,
    val searchTitle: String,
    val year: String?,
    val tagline: String?,
    val overview: String,
    /** English story when it adds something the Arabic one does not have. */
    val englishOverview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double?,
    val voteCount: Int?,
    val genres: List<String>,
    val facts: List<Pair<String, String>>,
    val region: String,
    val providers: RegionProviders?,
    val seasons: List<SeasonInfo>,
    val seasonDetails: List<SeasonDetail>,
    val latestAired: EpisodeMark?,
    val cast: List<CastMember>,
    val trailers: List<Trailer>,
    val reviews: List<ReviewInfo>,
    val recommendations: List<SearchResult>,
    val imdbId: String?,
    /** True when TMDB has no Arabic story, so [overview] is English and gets translated. */
    val overviewIsEnglish: Boolean = false,
    val originalLanguage: String? = null,
    val genreIds: List<Int> = emptyList(),
    /** Production countries as ISO codes, e.g. ["SA"]. */
    val countries: List<String> = emptyList(),
) {
    /** "🇸🇦 سعودي" style label(s) for the header. */
    val countryText: String?
        get() = countries.mapNotNull { c -> countryLabel(c)?.let { "${countryFlag(c).orEmpty()} $it".trim() } }
            .take(2).joinToString("، ").ifBlank { null }

    /** Services from our three that TMDB says carry this title in the region. */
    val availableServices: List<StreamingService>
        get() = providers?.streaming.orEmpty().mapNotNull { StreamingService.forProvider(it) }.distinct()

    fun toWatchItem(existing: WatchItem?, links: Map<StreamingService, String>?): WatchItem {
        val base = existing ?: WatchItem(id = id, type = type, title = title)
        return base.copy(
            title = title,
            searchTitle = searchTitle,
            posterPath = posterPath,
            providerIds = providers?.streaming?.map { it.id } ?: base.providerIds,
            seasons = seasons.ifEmpty { base.seasons },
            latestAired = latestAired ?: base.latestAired,
            directLinks = links?.mapKeys { it.key.key } ?: base.directLinks,
        )
    }

    companion object {
        fun from(type: MediaType, ar: Details, en: Details?, region: String): DetailInfo {
            val arOverview = ar.overview?.trim().orEmpty()
            val enOverview = en?.overview?.trim().orEmpty()
            val overview = arOverview.ifBlank { enOverview }
            val englishOverview = enOverview.takeIf { it.isNotBlank() && arOverview.isNotBlank() && it != arOverview }

            val trailers = (ar.videos?.results.orEmpty() + en?.videos?.results.orEmpty())
                .filter { it.site == "YouTube" && it.key.isNotBlank() && it.type in setOf("Trailer", "Teaser") }
                .distinctBy { it.key }
                .sortedWith(compareByDescending<com.alyoushw.watchhub.data.Video> { it.type == "Trailer" }.thenByDescending { it.official })
                .take(6)
                .map { Trailer(it.key, it.name) }

            val reviews = en?.reviews?.results.orEmpty()
                .filter { it.content.isNotBlank() }
                .take(5)
                .map { ReviewInfo(it.author, it.authorDetails?.rating, it.content.trim(), it.createdAt?.take(10)) }

            return DetailInfo(
                type = type,
                id = ar.id,
                title = ar.displayTitle.ifBlank { en?.displayTitle.orEmpty() },
                searchTitle = en?.displayTitle?.ifBlank { null } ?: ar.displayTitle,
                year = ar.year,
                tagline = (ar.tagline?.ifBlank { null } ?: en?.tagline?.ifBlank { null }),
                overview = overview,
                englishOverview = englishOverview,
                posterPath = ar.posterPath ?: en?.posterPath,
                backdropPath = ar.backdropPath ?: en?.backdropPath,
                rating = ar.voteAverage?.takeIf { it > 0 },
                voteCount = ar.voteCount,
                genres = ar.genres.map { it.name }.filter { it.isNotBlank() },
                facts = facts(type, ar),
                region = region,
                providers = ar.providersFor(region),
                seasons = ar.seasonInfo(),
                seasonDetails = ar.seasons
                    .filter { it.seasonNumber > 0 }
                    .map {
                        SeasonDetail(
                            number = it.seasonNumber,
                            name = it.name?.ifBlank { null } ?: "الموسم ${it.seasonNumber}",
                            episodes = it.episodeCount,
                            year = it.airDate?.take(4),
                            overview = it.overview.orEmpty(),
                            posterPath = it.posterPath,
                        )
                    },
                latestAired = ar.latestAired(),
                imdbId = (ar.imdbId ?: ar.externalIds?.imdbId)?.takeIf { it.startsWith("tt") },
                overviewIsEnglish = arOverview.isBlank() && enOverview.isNotBlank(),
                originalLanguage = ar.originalLanguage,
                genreIds = ar.genres.map { it.id }.filter { it > 0 },
                cast = ar.credits?.cast.orEmpty().take(15).map { CastMember(it.id, it.name, it.character, it.profilePath) },
                countries = ar.originCountry.ifEmpty { ar.productionCountries.map { it.code } }.filter { it.isNotBlank() }.distinct(),
                trailers = trailers,
                reviews = reviews,
                recommendations = ar.recommendations?.results.orEmpty()
                    .map { if (it.mediaType == null) it.copy(mediaType = type.path) else it }
                    .filter { it.posterPath != null && it.type != null }
                    .distinctBy { "${it.mediaType}:${it.id}" }
                    .take(15),
            )
        }

        private fun facts(type: MediaType, d: Details): List<Pair<String, String>> = buildList {
            statusLabel(d.status)?.let { add("الحالة" to it) }
            (d.releaseDate ?: d.firstAirDate)?.takeIf { it.isNotBlank() }?.let {
                add((if (type == MediaType.TV) "أول عرض" else "تاريخ الصدور") to it)
            }
            if (type == MediaType.MOVIE) {
                d.runtime?.takeIf { it > 0 }?.let { add("المدة" to durationLabel(it)) }
                d.credits?.crew.orEmpty().filter { it.job == "Director" }.map { it.name }
                    .takeIf { it.isNotEmpty() }?.let { add("الإخراج" to it.joinToString("، ")) }
            } else {
                val seasons = d.numberOfSeasons
                val episodes = d.numberOfEpisodes
                if (seasons != null && episodes != null) add("المواسم" to "$seasons مواسم · $episodes حلقة")
                d.episodeRunTime.firstOrNull()?.takeIf { it > 0 }?.let { add("مدة الحلقة" to "~${durationLabel(it)}") }
                d.createdBy.map { it.name }.takeIf { it.isNotEmpty() }?.let { add("من تأليف" to it.joinToString("، ")) }
                d.networks.map { it.name }.takeIf { it.isNotEmpty() }?.let { add("القناة" to it.joinToString("، ")) }
            }
            d.originalLanguage?.let { add("اللغة الأصلية" to languageLabel(it)) }
            d.productionCountries.map { c -> countryLabel(c.code) ?: c.name }.distinct().takeIf { it.isNotEmpty() }
                ?.let { add("البلد" to it.joinToString("، ")) }
        }

        private fun statusLabel(status: String?): String? = when (status) {
            null, "" -> null
            "Returning Series" -> "مستمر، فيه مواسم جاية"
            "Ended" -> "انتهى"
            "Canceled" -> "انلغى"
            "In Production" -> "قيد الإنتاج"
            "Released" -> "صدر"
            "Planned", "Post Production", "Rumored" -> "قريباً"
            else -> status
        }

        private fun durationLabel(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h == 0 -> "$m دقيقة"
                m == 0 -> "$h ساعة"
                else -> "$h س $m د"
            }
        }

        fun languageLabel(code: String): String = when (code) {
            "en" -> "الإنجليزية"
            "ar" -> "العربية"
            "ja" -> "اليابانية"
            "ko" -> "الكورية"
            "es" -> "الإسبانية"
            "fr" -> "الفرنسية"
            "hi" -> "الهندية"
            "tr" -> "التركية"
            "de" -> "الألمانية"
            "it" -> "الإيطالية"
            "zh", "cn" -> "الصينية"
            "id" -> "الإندونيسية"
            "th" -> "التايلندية"
            "tl" -> "الفلبينية"
            else -> code.uppercase(Locale.US)
        }
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

    /** Direct pages on each service; null while still looking them up. */
    var links by mutableStateOf<Map<StreamingService, String>?>(null)
        private set

    private val key = WatchItem.keyOf(type, id)

    /** "If you liked this": same language and genres first; null until computed. */
    var similar by mutableStateOf<List<SearchResult>?>(null)
        private set

    init {
        load()
        viewModelScope.launch {
            val found = container.links.resolve(type, id)
            links = found
            if (found.isNotEmpty()) container.store.updateWatch(key) { it.copy(directLinks = found.mapKeys { e -> e.key.key }) }
        }
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
                    val saved = container.store.data.value.watchlist.firstOrNull { it.key == key }
                    if (saved != null) container.store.upsertWatch(info.toWatchItem(saved, links))
                    viewModelScope.launch { similar = preciseSimilar(info) }
                    DetailState.Loaded(info)
                }
            } catch (e: ApiException) {
                DetailState.Failed(e.message ?: "صار خطأ")
            }
        }
    }

    /**
     * Ranks look-alikes so the closest come first: an Indonesian horror film leads with other
     * Indonesian horror, then the same language in the main genre, then TMDB's broader picks.
     */
    private suspend fun preciseSimilar(info: DetailInfo): List<SearchResult> {
        val lang = info.originalLanguage
        val genres = info.genreIds
        val base = buildMap {
            put("sort_by", "popularity.desc")
            put("vote_count.gte", "5")
            if (lang != null) put("with_original_language", lang)
            // Saudi stays Saudi (not all Arabic), Korean stays Korean, …
            if (info.countries.isNotEmpty()) put("with_origin_country", info.countries.joinToString("|"))
        }
        suspend fun discover(withGenres: String?): List<SearchResult> = try {
            container.api.discover(
                type,
                if (withGenres != null) base + ("with_genres" to withGenres) else base,
                streamableOnly = false,
            )
        } catch (_: ApiException) {
            emptyList()
        }

        val recs = info.recommendations
        val sameLangSameGenre = recs.filter { r -> r.originalLanguage == lang && r.genreIds.any { it in genres } }
        val strict = if (genres.isNotEmpty()) discover(genres.take(2).joinToString(",")) else emptyList()
        val loose = if (strict.size < 12 && genres.isNotEmpty()) discover(genres.first().toString()) else emptyList()
        val sameLang = recs.filter { it.originalLanguage == lang }
        val sameGenre = recs.filter { r -> r.genreIds.any { it in genres } }

        return (sameLangSameGenre + strict + loose + sameLang + sameGenre + recs)
            .filter { it.id != info.id && it.posterPath != null }
            .map { if (it.mediaType == null) it.copy(mediaType = type.path) else it }
            .distinctBy { it.id }
            .take(30)
    }

    fun addToWatchlist(info: DetailInfo) {
        viewModelScope.launch { container.store.upsertWatch(info.toWatchItem(null, links)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    type: MediaType,
    id: Int,
    onBack: () -> Unit,
    onOpen: (MediaType, Int) -> Unit,
    onOpenPerson: (Int) -> Unit = {},
    onOpenXtreamSeries: (String) -> Unit = {},
) {
    val container = appContainer()
    val vm: DetailViewModel = viewModel { DetailViewModel(container, type, id) }
    val title = (vm.state as? DetailState.Loaded)?.info?.title.orEmpty()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(modifier = Modifier.focusFrame(), onClick = onBack) {
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
                is DetailState.Loaded -> DetailContent(s.info, vm.links, vm.similar, onAdd = { vm.addToWatchlist(s.info) }, onOpen = onOpen, onOpenPerson = onOpenPerson, onOpenXtreamSeries = onOpenXtreamSeries)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    info: DetailInfo,
    links: Map<StreamingService, String>?,
    similar: List<SearchResult>?,
    onAdd: () -> Unit,
    onOpen: (MediaType, Int) -> Unit,
    onOpenPerson: (Int) -> Unit,
    onOpenXtreamSeries: (String) -> Unit,
) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by container.store.data.collectAsStateWithLifecycle()
    val saved = data.watchlist.firstOrNull { it.key == WatchItem.keyOf(info.type, info.id) }
    val subscribedKeys = data.subscriptions.mapNotNull { it.serviceKey }.toSet()
    val onWatch: (StreamingService) -> Unit = { service -> context.watchOn(service, links?.get(service), info.searchTitle) }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Header(info) {
                XtreamWatchButton(
                    isSeries = info.type == MediaType.TV,
                    tmdbId = info.id,
                    titles = listOf(info.title, info.searchTitle),
                    year = info.year,
                    onOpenSeries = onOpenXtreamSeries,
                )
            }
        }

        item { WatchButtons(info, links, subscribedKeys, onWatch) }

        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saved == null) {
                    OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().focusFrame()) {
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
                    if (info.type == MediaType.TV) EpisodeTracker(saved)
                    TextButton(modifier = Modifier.focusFrame(), onClick = { scope.launch { container.store.removeWatch(saved.key) } }) {
                        Text("شيله من قائمتي", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (info.trailers.isEmpty()) {
            // No trailer on TMDB: search YouTube for one.
            item {
                OutlinedButton(
                    modifier = Modifier.padding(horizontal = 16.dp).focusFrame(),
                    onClick = {
                        // Arabic shows are found on YouTube by their Arabic name ("اعلان"/promo), others in English.
                        val text = if (info.originalLanguage == "ar") {
                            "${info.title} ${info.year.orEmpty()} اعلان"
                        } else {
                            "${info.searchTitle} ${info.year.orEmpty()} trailer"
                        }
                        val q = java.net.URLEncoder.encode(text.replace("  ", " ").trim(), "UTF-8")
                        context.openUrl("https://www.youtube.com/results?search_query=$q")
                    },
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFFFF0000))
                    Text("  ابحث عن الإعلان في يوتيوب")
                }
            }
        }
        if (info.trailers.isNotEmpty()) {
            item { Trailers(info.trailers) { context.openUrl(it.url) } }
        }

        item { Story(info) }

        if (info.facts.isNotEmpty() || info.genres.isNotEmpty()) {
            item { Facts(info) }
        }

        if (info.type == MediaType.TV && info.seasonDetails.isNotEmpty()) {
            item { SectionTitle("المواسم", Modifier.padding(horizontal = 16.dp)) }
            items(info.seasonDetails, key = { "season-${it.number}" }) { SeasonCard(it) }
        }

        if (info.cast.isNotEmpty()) {
            item { CastRow(info.cast, onOpenPerson) }
        }

        item { RatingAndReviews(info) }

        item { OtherProviders(info) }

        val lookAlikes = similar ?: info.recommendations
        if (lookAlikes.isNotEmpty()) {
            item {
                val closest = listOfNotNull(
                    info.genres.firstOrNull(),
                    info.originalLanguage?.let { DetailInfo.languageLabel(it) },
                ).joinToString(" · ")
                TitleRow(
                    title = "إذا عجبك، بيعجبك",
                    subtitle = if (similar != null && closest.isNotBlank()) "الأقرب أول: $closest" else null,
                    items = lookAlikes,
                    onOpen = { r -> r.type?.let { onOpen(it, r.id) } },
                )
            }
        }

        item {
            Text(
                "البيانات من TMDB، وبيانات التوفر من JustWatch، والروابط المباشرة من Wikidata.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun Header(info: DetailInfo, extra: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth()) {
        // The wide image stays as a faint background; the poster is the face of the page.
        if (info.backdropPath != null) {
            AsyncImage(
                model = TmdbApi.image(info.backdropPath, "w780"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.22f,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Poster(info.posterPath, width = 140.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(info.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (info.searchTitle != info.title) {
                        Text(info.searchTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val kind = if (info.type == MediaType.TV) "مسلسل" else "فيلم"
                    Text(
                        listOfNotNull(kind, info.countryText, info.year, info.genres.take(2).joinToString("، ").ifBlank { null })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ratingLabel(info.rating)?.let { rating ->
                        val votes = info.voteCount?.let { " · " + String.format(Locale.US, "%,d", it) + " تقييم" }.orEmpty()
                        Text(
                            "$rating / 10$votes",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (info.type == MediaType.TV) StatusBadge(info.id)
                    BadgeRow(info.availableServices)
                    extra()
                }
            }
            info.tagline?.let {
                TranslatedText(
                    it,
                    isEnglish = it.none { c -> c in '\u0600'..'\u06FF' },
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    quote = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchButtons(
    info: DetailInfo,
    links: Map<StreamingService, String>?,
    subscribedKeys: Set<String>,
    onWatch: (StreamingService) -> Unit,
) {
    // Services TMDB lists for the region, plus any we have a direct page for.
    val services = (info.availableServices + links?.keys.orEmpty()).distinct()

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        services.forEach { service ->
            val direct = links?.get(service)
            Button(
                onClick = { onWatch(service) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(service.color), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(52.dp).focusFrame(),
            ) {
                when {
                    direct != null -> Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    links == null -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else -> Icon(Icons.Filled.Search, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (direct != null || links == null) "شاهد على ${service.label}" else "ابحث عنه في ${service.label}",
                    fontWeight = FontWeight.Bold,
                )
                if (service.key in subscribedKeys) {
                    Text("  ✓ مشترك", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (services.isEmpty()) {
            Text(
                "ما لقينا هذا العمل على نتفلكس أو برايم أو كرنشي رول في منطقتك (${info.region}). تقدر تدوّر عليه بنفسك:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CustomAppButtons(info.searchTitle)

        val others = StreamingService.entries.filter { it !in services }
        if (others.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                others.forEach { service ->
                    OutlinedButton(modifier = Modifier.focusFrame(), onClick = { onWatch(service) }) { Text("ابحث في ${service.label}") }
                }
            }
        }
    }
}

/** The user's own extra apps, picked once in Settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomAppButtons(title: String) {
    val container = appContainer()
    val context = LocalContext.current
    val apps by container.settings.customApps.collectAsStateWithLifecycle()
    if (apps.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        apps.forEach { pkg ->
            val label = remember(pkg) { context.appLabel(pkg) }
            if (label != null) {
                OutlinedButton(modifier = Modifier.focusFrame(), onClick = { context.openCustomApp(pkg, title) }) {
                    AppIcon(pkg, size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(label)
                }
            }
        }
    }
}

@Composable
fun Trailers(trailers: List<Trailer>, onPlay: (Trailer) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("الإعلانات", Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(trailers, key = { it.key }) { trailer ->
                Column(Modifier.width(240.dp).clickable { onPlay(trailer) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                        AsyncImage(
                            model = trailer.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color(0xFFFF0000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                        }
                    }
                    Text(trailer.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Story(info: DetailInfo) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("القصة")
        if (info.overview.isBlank()) {
            Text("ما فيه وصف لهذا العمل للحين.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TranslatedText(info.overview, isEnglish = info.overviewIsEnglish, style = MaterialTheme.typography.bodyLarge)
        }
        info.englishOverview?.let { english ->
            var open by remember { mutableStateOf(false) }
            TextButton(modifier = Modifier.focusFrame(), onClick = { open = !open }) {
                Text(if (open) "إخفاء القصة المفصلة" else "القصة بالتفصيل")
            }
            if (open) {
                TranslatedText(
                    english,
                    isEnglish = true,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Shows [text] in Arabic: English text is translated on the device automatically, with a
 * button to see the original. Arabic text is shown as is.
 */
@Composable
fun TranslatedText(
    text: String,
    isEnglish: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    quote: Boolean = false,
) {
    var translated by remember(text) { mutableStateOf(if (isEnglish) ArabicTranslator.cached(text) else null) }
    var failed by remember(text) { mutableStateOf(false) }
    var showOriginal by remember(text) { mutableStateOf(false) }
    if (isEnglish) {
        LaunchedEffect(text) {
            if (translated == null) {
                translated = ArabicTranslator.translate(text)
                failed = translated == null
            }
        }
    }
    val arabic = translated
    val showArabic = isEnglish && arabic != null && !showOriginal
    val shown = when {
        !isEnglish -> text
        showArabic -> arabic.orEmpty()
        else -> text
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            if (quote) "«$shown»" else shown,
            style = if (isEnglish && !showArabic) style.copy(textDirection = TextDirection.Ltr) else style,
            textAlign = if (isEnglish && !showArabic) TextAlign.Left else TextAlign.Start,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isEnglish) {
            when {
                arabic != null -> TextButton(
                    modifier = Modifier.focusFrame(),
                    onClick = { showOriginal = !showOriginal },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (showOriginal) "اعرضه بالعربي" else "مترجم آلياً · اعرض الأصل",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                failed -> Text(
                    "ما قدرنا نترجمه الحين (الترجمة تحتاج إنترنت أول مرة)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text("جاري الترجمة…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Facts(info: DetailInfo) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("معلومات")
        info.facts.forEach { (label, value) ->
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
        if (info.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                info.genres.forEach { AssistChip(modifier = Modifier.focusFrame(), onClick = {}, label = { Text(it) }) }
            }
        }
    }
}

@Composable
private fun SeasonCard(season: SeasonDetail) {
    var open by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(enabled = season.overview.isNotBlank()) { open = !open }
            .animateContentSize(),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Poster(season.posterPath, width = 50.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(season.name, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull("${season.episodes} حلقة", season.year).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (season.overview.isNotBlank()) {
                    Text(
                        season.overview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (open) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CastRow(cast: List<CastMember>, onOpenPerson: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("الممثلين", Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cast) { member ->
                Column(
                    Modifier
                        .width(84.dp)
                        .focusHighlight(RoundedCornerShape(12.dp))
                        .clickable(enabled = member.id > 0) { onOpenPerson(member.id) }
                        .padding(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (member.profilePath != null) {
                            AsyncImage(
                                model = TmdbApi.image(member.profilePath, "w185"),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(member.name.take(1), fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(member.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    member.character?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingAndReviews(info: DetailInfo) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("تقييم المشاهدين")
        val rating = info.rating
        if (rating == null) {
            Text("ما فيه تقييمات كافية للحين.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    String.format(Locale.US, "%.1f", rating),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Column {
                    Text("من 10", style = MaterialTheme.typography.bodyMedium)
                    info.voteCount?.let {
                        Text(
                            "حسب " + String.format(Locale.US, "%,d", it) + " مشاهد على TMDB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (info.reviews.isNotEmpty()) {
            Text("آراء المشاهدين (بالإنجليزي)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            info.reviews.forEach { ReviewCard(it) }
        }
    }
}

@Composable
private fun ReviewCard(review: ReviewInfo) {
    var open by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(12.dp)).clickable { open = !open }.animateContentSize(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(review.author, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                review.rating?.let { RatingChip(it) }
            }
            TranslatedText(
                review.content,
                isEnglish = true,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (open) Int.MAX_VALUE else 5,
            )
            Text(
                if (open) "أقل" else "اقرأ الرأي كامل",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun OtherProviders(info: DetailInfo) {
    val context = LocalContext.current
    val others = info.providers?.streaming.orEmpty().filter { StreamingService.forProvider(it) == null }
    val paid = info.providers?.paid.orEmpty()
    if (others.isEmpty() && paid.isEmpty() && info.providers?.link == null && info.imdbId == null) return

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("منصات ثانية (${info.region})")
        others.forEach { provider ->
            ProviderLine(provider) {
                if (!context.openProviderApp(provider.name, info.searchTitle)) {
                    info.providers?.link?.let { context.openUrl(it) }
                }
            }
        }
        if (paid.isNotEmpty()) {
            Text(
                "للإيجار أو الشراء: " + paid.joinToString("، ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        info.providers?.link?.let { link ->
            TextButton(modifier = Modifier.focusFrame(), onClick = { context.openUrl(link) }) { Text("كل خيارات المشاهدة على TMDB") }
        }
        info.imdbId?.let { imdb ->
            TextButton(modifier = Modifier.focusFrame(), onClick = { context.openUrl("https://www.imdb.com/title/$imdb/") }) { Text("افتحه في IMDb") }
        }
    }
}

@Composable
private fun ProviderLine(provider: ProviderDto, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(
            model = TmdbApi.image(provider.logoPath, "w92"),
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
        )
        Text(provider.name, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
