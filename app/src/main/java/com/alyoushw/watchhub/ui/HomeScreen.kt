package com.alyoushw.watchhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.alyoushw.watchhub.data.RowSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.SearchResult
import com.alyoushw.watchhub.data.StreamingService
import com.alyoushw.watchhub.data.ApiException
import com.alyoushw.watchhub.data.WatchItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeSection(
    val id: String,
    val title: String,
    val items: List<SearchResult>,
    val subtitle: String? = null,
    val service: StreamingService? = null,
    val ranked: Boolean = false,
    val wide: Boolean = false,
    /** Lets "عرض الكل" load the whole list page by page; null for rows that can't. */
    val source: RowSource? = null,
)

/** ISO codes of the Gulf states, as TMDB's with_origin_country "OR" list. */
private const val GULF_COUNTRIES = "SA|AE|KW|BH|QA|OM"

/** Regional services worth a row of their own when TMDB lists them for the user's region. */
private val REGIONAL_KEYWORDS = listOf("shahid", "شاهد", "stc", "jawwy", "osn", "starz", "tod", "weyyak", "viu", "yango", "apple tv")

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    var type by mutableStateOf(container.homeType)
        private set
    var sections by mutableStateOf<List<HomeSection>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val cache = mutableMapOf<MediaType, List<HomeSection>>()
    private var job: Job? = null
    private var lastError: String? = null

    private var loadedFor: String? = null
    private val loadedAt = mutableMapOf<MediaType, Long>()

    /** TMDB changes all the time; reload when the shown lists are more than 3 hours old. */
    fun refreshIfStale() {
        val at = loadedAt[type] ?: return
        if (System.currentTimeMillis() - at > 3 * 60 * 60 * 1000L) {
            cache.remove(type)
            load()
        }
    }

    /** Loads once per API key + region; returning to the tab keeps what is already shown. */
    fun ensureLoaded(apiKey: String, region: String) {
        val signature = "$apiKey|$region"
        if (signature == loadedFor) return
        loadedFor = signature
        refresh()
    }

    fun selectType(value: MediaType) {
        if (value == type) return
        type = value
        container.homeType = value
        load()
    }

    fun refresh() {
        cache.clear()
        load()
    }

    fun load() {
        val t = type
        cache[t]?.let {
            sections = it
            error = null
            return
        }
        if (container.settings.apiKey.value.isBlank()) return
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            error = null
            sections = emptyList()
            lastError = null
            val result = buildSections(t)
            if (result.isEmpty()) {
                error = lastError ?: "ما قدرنا نجيب المحتوى"
            } else {
                cache[t] = result
                loadedAt[t] = System.currentTimeMillis()
            }
            sections = result
            loading = false
        }
    }

    /** Anime rows from TMDB: Japanese animation, as series and films. */
    private suspend fun buildAnimeSections(): List<HomeSection> = coroutineScope {
        val today = LocalDate.now()
        val anime = mapOf("with_genres" to "16", "with_original_language" to "ja")
        fun tv(vararg extra: Pair<String, String>) = RowSource.Discover(MediaType.TV, anime + extra, streamableOnly = false)
        val jobs = buildList {
            add(async {
                sourced(
                    "anime-airing", "ينعرض الحين",
                    // Everything with an episode in the last two weeks or the coming week: the full current season.
                    tv(
                        "sort_by" to "popularity.desc",
                        "air_date.gte" to today.minusDays(14).toString(),
                        "air_date.lte" to today.plusDays(7).toString(),
                    ),
                    subtitle = "كل الأنمي اللي تنزل له حلقات هالأيام",
                )
            })
            StreamingService.entries.filter { it.tmdbIds.isNotEmpty() }.forEach { service ->
                add(async {
                    sourced(
                        "anime-top-${service.key}", "أفضل 10 أنمي على ${service.label}",
                        RowSource.Discover(
                            MediaType.TV,
                            anime + mapOf("sort_by" to "popularity.desc", "with_watch_providers" to service.tmdbIds.joinToString("|")),
                        ),
                        service = service, ranked = true, limit = 10,
                    )
                })
            }
            add(async {
                sourced(
                    "anime-top-rated", "الأعلى تقييماً من المشاهدين",
                    tv("sort_by" to "vote_average.desc", "vote_count.gte" to "300"),
                    ranked = true, limit = 10,
                )
            })
            add(async { sourced("anime-popular", "الأشهر على الإطلاق", tv("sort_by" to "popularity.desc")) })
            add(async {
                sourced(
                    "anime-movies", "أفلام أنمي",
                    RowSource.Discover(MediaType.MOVIE, anime + mapOf("sort_by" to "popularity.desc", "vote_count.gte" to "50"), streamableOnly = false),
                )
            })
            add(async {
                sourced("anime-upcoming", "قريباً", tv("sort_by" to "popularity.desc", "first_air_date.gte" to today.toString()))
            })
        }
        jobs.awaitAll().filterNotNull()
    }

    private suspend fun buildSections(t: MediaType): List<HomeSection> {
        if (t == MediaType.ANIME) return buildAnimeSections()
        return buildTmdbSections(t)
    }

    private suspend fun buildTmdbSections(t: MediaType): List<HomeSection> = coroutineScope {
        val kind = if (t == MediaType.TV) "مسلسلات" else "أفلام"
        val today = LocalDate.now()
        val dateField = if (t == MediaType.TV) "air_date" else "primary_release_date"

        val firstDateField = if (t == MediaType.TV) "first_air_date" else "primary_release_date"

        // Every service TMDB (JustWatch) lists for the region; used to find Shahid / STC tv by name
        // and to add rows for other regional services (OSN+, STARZPLAY, …).
        val allProviders = try {
            container.api.regionProviders(t)
        } catch (_: ApiException) {
            emptyList()
        }
        val regional = allProviders
            .filter { p -> StreamingService.forProvider(p) == null && REGIONAL_KEYWORDS.any { it in p.name.lowercase() } }
            .distinctBy { it.name.lowercase().substringBefore(" ") }
            .take(6)
        fun idsFor(service: StreamingService): Set<Int> =
            service.tmdbIds + allProviders.filter { StreamingService.forProvider(it) == service }.map { it.id }

        val jobs = buildList {
            add(async { sourced("trending", "الرائج هذا الأسبوع", RowSource.Trending(t), wide = true) })
            add(async { forYou(t) })
            // Gulf productions (Saudi, UAE, Kuwait, Bahrain, Qatar, Oman), from all of TMDB.
            val gulf = mapOf("with_origin_country" to GULF_COUNTRIES)
            if (t == MediaType.TV) {
                // What has episodes out right now (last 10 days / next 3), Gulf and all Arabic.
                val airing = mapOf(
                    "sort_by" to "popularity.desc",
                    "air_date.gte" to today.minusDays(10).toString(),
                    "air_date.lte" to today.plusDays(3).toString(),
                )
                add(async {
                    sourced(
                        "gulf-airing", "مسلسلات خليجية تنعرض الحين",
                        RowSource.Discover(t, gulf + airing, streamableOnly = false),
                        subtitle = "نزلت لها حلقات هالأيام",
                    )
                })
                add(async {
                    sourced(
                        "arabic-airing", "مسلسلات عربية تنعرض الحين",
                        RowSource.Discover(t, airing + ("with_original_language" to "ar"), streamableOnly = false),
                        subtitle = "خليجي ومصري وشامي وغيرها",
                    )
                })
            }
            add(async {
                sourced(
                    "gulf-new", "${if (t == MediaType.TV) "مسلسلات" else "أفلام"} خليجية جديدة",
                    // Started in the last ~5 months (by first air date), so last Ramadan's shows drop off.
                    RowSource.Discover(
                        t,
                        gulf + mapOf(
                            "sort_by" to "$firstDateField.desc",
                            "$firstDateField.gte" to today.minusDays(150).toString(),
                            "$firstDateField.lte" to today.toString(),
                        ),
                        streamableOnly = false,
                    ),
                    subtitle = "بدأت خلال آخر ٥ شهور، الأحدث أول",
                )
            })
            add(async {
                sourced(
                    "gulf-popular", "الأشهر خليجياً",
                    RowSource.Discover(t, gulf + ("sort_by" to "popularity.desc"), streamableOnly = false),
                )
            })
            regional.forEach { provider ->
                add(async {
                    sourced(
                        "provider-${provider.id}", "أفضل 10 $kind على ${provider.name}",
                        RowSource.Discover(t, mapOf("with_watch_providers" to "${provider.id}", "sort_by" to "popularity.desc")),
                        ranked = true, limit = 10,
                    )
                })
            }
            StreamingService.entries.forEach { service ->
                val ids = idsFor(service)
                // Shahid / STC tv only get a row when TMDB lists them for the region.
                if (ids.isEmpty()) return@forEach
                add(async {
                    sourced(
                        "top-${service.key}", "أفضل 10 $kind على ${service.label}",
                        RowSource.Discover(
                            t,
                            mapOf("with_watch_providers" to ids.joinToString("|"), "sort_by" to "popularity.desc"),
                        ),
                        service = service, ranked = true, limit = 10,
                    )
                })
            }
            // These rows cover everything on TMDB, not only what the user's services carry.
            add(async {
                sourced(
                    "new", "الجديد",
                    RowSource.Discover(
                        t,
                        mapOf(
                            "sort_by" to "popularity.desc",
                            "$dateField.gte" to today.minusDays(120).toString(),
                            "$dateField.lte" to today.toString(),
                        ),
                        streamableOnly = false,
                    ),
                    subtitle = "نزل خلال آخر ٤ شهور",
                )
            })
            add(async {
                sourced(
                    "top-rated", "الأعلى تقييماً من المشاهدين",
                    RowSource.Discover(
                        t,
                        mapOf(
                            "sort_by" to "vote_average.desc",
                            "vote_count.gte" to if (t == MediaType.TV) "200" else "500",
                        ),
                        streamableOnly = false,
                    ),
                )
            })
            add(async {
                sourced("popular", "الأشهر", RowSource.Discover(t, mapOf("sort_by" to "popularity.desc"), streamableOnly = false))
            })
        }
        jobs.awaitAll().filterNotNull()
    }

    /** A row whose items come from [source]'s first page; "عرض الكل" continues from there. */
    private suspend fun sourced(
        id: String,
        title: String,
        source: RowSource,
        subtitle: String? = null,
        service: StreamingService? = null,
        ranked: Boolean = false,
        wide: Boolean = false,
        limit: Int = 20,
    ): HomeSection? = section(id, title, subtitle, service, ranked, wide, limit) {
        container.api.page(source, 1).results
    }?.copy(source = source)

    /** Recommendations based on the latest titles in the user's watchlist. */
    private suspend fun forYou(t: MediaType): HomeSection? {
        val watchlist = container.store.data.value.watchlist
        val seeds = watchlist.filter { it.type == t }.sortedByDescending { it.addedAt }.take(3)
        if (seeds.isEmpty()) return null
        val saved = watchlist.map { it.key }.toSet()
        return section(
            id = "for-you",
            title = "مرشّح لك",
            subtitle = "لأنك أضفت " + seeds.joinToString("، ") { it.title },
        ) {
            val lists = coroutineScope { seeds.map { async { api().recommendations(it.type, it.id) } }.awaitAll() }
            // Interleave so each seed contributes to the top of the row.
            val merged = mutableListOf<SearchResult>()
            val longest = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let(merged::add) }
            merged.filter { r ->
                val type = r.type
                type == null || WatchItem.keyOf(type, r.id) !in saved
            }
        }
    }

    private fun api() = container.api

    private suspend fun section(
        id: String,
        title: String,
        subtitle: String? = null,
        service: StreamingService? = null,
        ranked: Boolean = false,
        wide: Boolean = false,
        limit: Int = 20,
        fetch: suspend () -> List<SearchResult>,
    ): HomeSection? = try {
        val items = fetch().filter { it.posterPath != null }.distinctBy { it.id }.take(limit)
        if (items.isEmpty()) null else HomeSection(id, title, items, subtitle, service, ranked, wide)
    } catch (e: ApiException) {
        lastError = e.message
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never let one bad row crash the home screen.
        lastError = "صار خطأ: ${e.javaClass.simpleName}"
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (MediaType, Int) -> Unit,
    onOpenSettings: () -> Unit,
    onSeeAll: (HomeSection) -> Unit,
    onOpenXtreamSeries: (String) -> Unit = {},
) {
    val container = appContainer()
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val apiKey by container.settings.apiKey.collectAsStateWithLifecycle()
    val region by container.settings.region.collectAsStateWithLifecycle()

    // Load when the key is first added or the region changes.
    LaunchedEffect(apiKey, region) {
        if (apiKey.isNotBlank()) vm.ensureLoaded(apiKey, region)
    }
    LifecycleResumeEffect(Unit) {
        vm.refreshIfStale()
        onPauseOrDispose { }
    }

    var live by rememberSaveable { mutableStateOf(false) }
    val header: @Composable () -> Unit = {
        HeroTabs(
            selected = vm.type,
            onSelect = { live = false; vm.selectType(it) },
            onRefresh = vm::refresh,
            live = live,
            onLive = { live = true },
        )
    }
    Box(Modifier.fillMaxSize()) {
        when {
            live -> Column {
                header()
                XtreamLiveChannels(onOpenSettings)
            }

            apiKey.isBlank() -> Column {
                header()
                // "تابع المشاهدة" never waits for (or depends on) the TMDB rows.
                ContinueWatchingRow(onOpenXtreamSeries, onOpenTitle = onOpen, onOpenLive = { live = true })
                MessageBox(
                    title = "خطوة وحدة قبل نبدأ",
                    body = "حط مفتاح TMDB المجاني في الإعدادات عشان نعرض لك أفضل الأعمال في كل منصة.",
                    actionLabel = "افتح الإعدادات",
                    onAction = onOpenSettings,
                )
            }

            vm.loading && vm.sections.isEmpty() -> Column {
                header()
                ContinueWatchingRow(onOpenXtreamSeries, onOpenTitle = onOpen, onOpenLive = { live = true })
                CenteredLoading()
            }

            vm.error != null && vm.sections.isEmpty() -> Column {
                header()
                ContinueWatchingRow(onOpenXtreamSeries, onOpenTitle = onOpen, onOpenLive = { live = true })
                MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::refresh)
            }

            else -> {
                // The trending row becomes the big banner; for anime the first row feeds it.
                val hero = vm.sections.firstOrNull { it.wide } ?: vm.sections.firstOrNull()
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    item(key = "hero") {
                        HeroCarousel(
                            items = hero?.items.orEmpty(),
                            header = header,
                            onOpen = { item -> item.type?.let { onOpen(it, item.id) } },
                        )
                    }
                    item(key = "continue") { ContinueWatchingRow(onOpenXtreamSeries, onOpenTitle = onOpen, onOpenLive = { live = true }) }
                    items(vm.sections.filter { !(it.wide && it.id == hero?.id) }, key = { it.id }) { section ->
                        TitleRow(
                            title = section.title,
                            items = section.items,
                            subtitle = section.subtitle,
                            service = section.service,
                            ranked = section.ranked,
                            wide = false,
                            onSeeAll = section.source?.let { { onSeeAll(section) } },
                            onOpen = { item -> item.type?.let { onOpen(it, item.id) } },
                        )
                    }
                }
            }
        }
    }
}
