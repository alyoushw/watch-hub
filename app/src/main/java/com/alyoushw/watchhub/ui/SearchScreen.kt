package com.alyoushw.watchhub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.runtime.saveable.rememberSaveable
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.R
import com.alyoushw.watchhub.data.ApiException
import com.alyoushw.watchhub.data.AssistantResult
import com.alyoushw.watchhub.data.DiscoverFilter
import com.alyoushw.watchhub.data.Genre
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.Origin
import com.alyoushw.watchhub.data.SavedFilter
import com.alyoushw.watchhub.data.SearchResult
import com.alyoushw.watchhub.data.SmartQuery
import com.alyoushw.watchhub.data.SmartSearch
import com.alyoushw.watchhub.data.SortMode
import com.alyoushw.watchhub.data.StreamingService
import com.alyoushw.watchhub.data.WatchItem
import com.alyoushw.watchhub.data.countryFlag
import com.alyoushw.watchhub.data.countryLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(
        val items: List<SearchResult>,
        /** Explains what we understood, e.g. "فهمت إنك تبي:". Null for a plain title search. */
        val heading: String? = null,
        val chips: List<String> = emptyList(),
        /** The filter behind these results, if any, for editing, saving and paging. */
        val filter: DiscoverFilter? = null,
        val canLoadMore: Boolean = false,
    ) : SearchState

    data class Failed(val message: String) : SearchState
}

class SearchViewModel(private val container: AppContainer) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var state by mutableStateOf<SearchState>(SearchState.Idle)
        private set
    var loadingMore by mutableStateOf(false)
        private set

    /** Why Claude was not used for the last request (shown under the results), if it wasn't. */
    var notice by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null
    private var page = 1

    fun onQueryChange(value: String) {
        query = value
        job?.cancel()
        if (value.isBlank()) {
            state = SearchState.Idle
            return
        }
        job = viewModelScope.launch {
            delay(600) // wait for the user to stop typing
            // Live results use the offline parser; Claude runs on search / voice to save credit.
            runSmart(value, useClaude = false)
        }
    }

    fun submit() {
        job?.cancel()
        if (query.isNotBlank()) job = viewModelScope.launch { runSmart(query, useClaude = true) }
    }

    /** Spoken or tapped request: show it in the box and run it right away. */
    fun ask(text: String) {
        query = text
        submit()
    }

    fun applyFilter(filter: DiscoverFilter, heading: String = "النتائج حسب الفلتر:") {
        job?.cancel()
        job = viewModelScope.launch {
            state = SearchState.Loading
            state = guard { filterResults(filter, heading) }
        }
    }

    fun recommendFromWatchlist() {
        job?.cancel()
        job = viewModelScope.launch {
            state = SearchState.Loading
            state = guard { fromWatchlist() }
        }
    }

    fun loadMore() {
        val current = state as? SearchState.Results ?: return
        val filter = current.filter ?: return
        if (loadingMore) return
        viewModelScope.launch {
            loadingMore = true
            try {
                val more = strictPages(filter, wanted = 12)
                state = current.copy(
                    items = (current.items + more).distinctBy { "${it.mediaType}:${it.id}" },
                    canLoadMore = page < totalPages,
                )
            } catch (_: ApiException) {
            }
            loadingMore = false
        }
    }

    private var totalPages = 1

    /**
     * Reads discover pages after [page] until [wanted] results pass [DiscoverFilter.matches]
     * (or 6 pages / the end), so filters like "سعودي + كوميدي" show only Saudi comedies.
     */
    private suspend fun strictPages(filter: DiscoverFilter, wanted: Int): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        var read = 0
        while (out.size < wanted && page < totalPages && read < 6) {
            val response = container.api.discoverPage(filter.type, filter.toParams(), page + 1, streamableOnly = false)
            page = response.page.coerceAtLeast(page + 1)
            totalPages = response.totalPages.coerceIn(1, 500)
            out += response.results.filter(filter::matches)
            read++
        }
        return out
    }

    private suspend fun guard(block: suspend () -> SearchState): SearchState = try {
        block()
    } catch (e: ApiException) {
        SearchState.Failed(e.message ?: "صار خطأ")
    }

    private suspend fun runSmart(text: String, useClaude: Boolean) {
        state = SearchState.Loading
        notice = null
        var query = SmartSearch.parse(text)
        var heading = "فهمت إنك تبي:"
        if (useClaude && container.assistant.isEnabled) {
            when (val ai = container.assistant.interpret(text)) {
                is AssistantResult.Understood -> {
                    query = ai.query
                    heading = "✨ ${ai.explanation}"
                }
                // Limit reached, no credit, offline…: keep the offline parser's answer.
                is AssistantResult.Unavailable -> notice = ai.reason
            }
        }
        state = guard {
            when (val q = query) {
                is SmartQuery.Plain -> SearchState.Results(container.api.search(q.text))
                is SmartQuery.Filter -> filterResults(q.filter, heading)
                is SmartQuery.Similar -> similar(q)
                SmartQuery.FromWatchlist -> fromWatchlist()
            }
        }
    }

    private suspend fun filterResults(filter: DiscoverFilter, heading: String): SearchState {
        page = 0
        totalPages = 1
        val items = strictPages(filter, wanted = 15).distinctBy { "${it.mediaType}:${it.id}" }
        return SearchState.Results(items, heading, filter.summary(), filter, canLoadMore = page < totalPages)
    }

    private suspend fun similar(q: SmartQuery.Similar): SearchState {
        // Spoken titles often run on ("شباب البومب سكه سفر"): try the whole thing, then shorter.
        val words = q.title.split(" ").filter { it.isNotBlank() }
        val attempts = listOf(q.title) + (words.size - 1 downTo 1).map { words.take(it).joinToString(" ") }
        var found: SearchResult? = null
        for (attempt in attempts.distinct()) {
            val hits = container.api.search(attempt)
            found = hits.firstOrNull { q.type == null || it.type == q.type } ?: hits.firstOrNull()
            if (found != null) break
        }
        val seed: SearchResult = found
            ?: return SearchState.Results(emptyList(), "ما لقينا «${q.title}». جرّب تكتب اسمه بالإنجليزي.")
        val seedType = seed.type
        if (seedType == null) {
            return SearchState.Results(emptyList(), "ما لقينا «${q.title}». جرّب تكتب اسمه بالإنجليزي.")
        }

        // Closest first: same country (as asked, or the seed's) AND all of the seed's main genres
        // (e.g. Saudi + comedy), then same country + its main genre, then TMDB's own picks.
        val countries = q.origin?.countries ?: seed.originCountry.takeIf { it.isNotEmpty() }?.joinToString("|")
        val language = q.origin?.language ?: seed.originalLanguage
        val mainGenres = seed.genreIds.take(2)
        suspend fun discoverWith(genres: String?): List<SearchResult> = try {
            container.api.discover(
                seedType,
                buildMap {
                    put("sort_by", "popularity.desc")
                    language?.let { put("with_original_language", it) }
                    countries?.let { put("with_origin_country", it) }
                    genres?.let { put("with_genres", it) }
                },
                streamableOnly = false,
            )
        } catch (_: ApiException) {
            emptyList()
        }
        val countrySet = countries?.split("|")?.toSet()
        fun sameCountry(r: SearchResult) =
            countrySet == null || r.originCountry.any { it in countrySet } || (r.originCountry.isEmpty() && r.originalLanguage == language)

        val strict = if (mainGenres.isNotEmpty()) {
            discoverWith(mainGenres.joinToString(",")).filter { r -> sameCountry(r) && r.genreIds.containsAll(mainGenres) }
        } else {
            emptyList()
        }
        val recs = container.api.recommendations(seedType, seed.id)
        val recsClose = recs.filter { r -> sameCountry(r) && mainGenres.firstOrNull()?.let { it in r.genreIds } != false }
        val loose = mainGenres.firstOrNull()?.let { g -> discoverWith("$g").filter(::sameCountry) }.orEmpty()
        val recsSameCountry = recs.filter(::sameCountry)
        val items = (strict + recsClose + loose + recsSameCountry + recs)
            .filter { it.id != seed.id }
            .distinctBy { "${it.mediaType}:${it.id}" }
        val where = q.origin?.label?.let { " ($it)" }.orEmpty()
        return SearchState.Results(items, "مشابه لـ «${seed.displayTitle}»$where:")
    }

    private suspend fun fromWatchlist(): SearchState {
        val watchlist = container.store.data.value.watchlist
        if (watchlist.isEmpty()) return SearchState.Failed("قائمتك فاضية. أضف كم عمل يعجبك وبنرشّح لك على ذوقك.")
        val saved = watchlist.map { it.key }.toSet()
        val recent = watchlist.sortedByDescending { it.addedAt }
        val tmdbSeeds = recent.filter { it.type != MediaType.ANIME }.take(5)
        val animeSeeds = recent.filter { it.type == MediaType.ANIME }.take(2)

        val lists = coroutineScope {
            val tmdb = tmdbSeeds.map { seed -> async { runCatchingApi { container.api.recommendations(seed.type, seed.id) } } }
            val anime = animeSeeds.map { seed ->
                async { runCatchingApi { container.jikan.recommendations(seed.id).take(10).map { it.toSearchResult() } } }
            }
            (tmdb + anime).awaitAll()
        }
        // Interleave so every seed contributes near the top.
        val merged = mutableListOf<SearchResult>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { list -> list.getOrNull(i)?.let(merged::add) }
        val items = merged
            .filter { r ->
                val type = r.type
                type == null || WatchItem.keyOf(type, r.id) !in saved
            }
            .distinctBy { "${it.mediaType}:${it.id}" }
        val names = (tmdbSeeds + animeSeeds).joinToString("، ") { it.title }
        return SearchState.Results(items, "مرشّح لك لأنك أضفت: $names")
    }

    private suspend fun runCatchingApi(block: suspend () -> List<SearchResult>): List<SearchResult> = try {
        block()
    } catch (_: ApiException) {
        emptyList()
    }
}

private val Examples = listOf(
    "مسلسل كوري درامي",
    "فيلم رعب إندونيسي",
    "أفضل أفلام أكشن على نتفلكس",
    "شي مشابه لـ Interstellar",
    "أنمي فانتازيا",
    "كوميدي عائلي جديد",
    "رشّح لي من قائمتي",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onOpen: (MediaType, Int) -> Unit, onOpenSettings: () -> Unit, onOpenXtreamSeries: (String) -> Unit = {}) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: SearchViewModel = viewModel { SearchViewModel(container) }
    val apiKey by container.settings.apiKey.collectAsStateWithLifecycle()
    val data by container.store.data.collectAsStateWithLifecycle()

    val accounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    var accountsOnly by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DiscoverFilter?>(null) }
    var savingFilter by remember { mutableStateOf<DiscoverFilter?>(null) }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) vm.ask(spoken)
        }
    }
    val startVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "قول وش تبي تشوف… مثلاً: مسلسل كوري درامي")
        try {
            voice.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "جوالك ما فيه خدمة التعرف على الصوت", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("وش تبي تشوف؟") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("اكتب أو تكلّم: «فيلم رعب إندونيسي»") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    Row {
                        if (vm.query.isNotEmpty()) {
                            IconButton(modifier = Modifier.focusFrame(), onClick = { vm.onQueryChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "مسح")
                            }
                        }
                        IconButton(modifier = Modifier.focusFrame(), onClick = startVoice) {
                            Icon(
                                painterResource(R.drawable.ic_mic),
                                contentDescription = "بحث بالصوت",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
            )

            // Filters, saved tastes and "from my list" in one scrollable row.
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    AssistChip(
                    modifier = Modifier.focusFrame(),
                        onClick = { editing = (vm.state as? SearchState.Results)?.filter ?: DiscoverFilter() },
                        label = { Text("الفلاتر") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
                if (accounts.isNotEmpty()) {
                    item {
                        FilterChip(
                            modifier = Modifier.focusFrame(),
                            selected = accountsOnly,
                            onClick = { accountsOnly = !accountsOnly },
                            label = { Text("حساباتي فقط") },
                        )
                    }
                }
                item {
                    AssistChip(modifier = Modifier.focusFrame(), onClick = vm::recommendFromWatchlist, label = { Text("✨ من قائمتي") })
                }
                items(data.savedFilters, key = { "saved-" + it.name }) { saved ->
                    AssistChip(modifier = Modifier.focusFrame(), onClick = { vm.applyFilter(saved.filter, "نمطك «${saved.name}»:") }, label = { Text("★ ${saved.name}") })
                }
            }

            // Films, series and channels from the user's own Xtream accounts, above everything else.
            XtreamSearchHits(vm.query, onOpenXtreamSeries)

            when {
                accountsOnly -> if (vm.query.isBlank()) {
                    MessageBox(title = "اكتب اسم قناة أو فيلم أو مسلسل", body = "ونطلع لك وش موجود منه في حساباتك.")
                }
                apiKey.isBlank() -> MessageBox(
                    title = "خطوة وحدة قبل نبدأ",
                    body = "التطبيق يجيب بيانات الأفلام والمسلسلات ووين متوفرة من TMDB. تحتاج مفتاح مجاني تحطه في الإعدادات.",
                    actionLabel = "افتح الإعدادات",
                    onAction = onOpenSettings,
                )

                else -> when (val s = vm.state) {
                    SearchState.Idle -> IdleHints(onAsk = vm::ask, onVoice = startVoice)
                    SearchState.Loading -> CenteredLoading()
                    is SearchState.Failed -> MessageBox(s.message, actionLabel = "حاول مرة ثانية", onAction = vm::submit)
                    is SearchState.Results -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        vm.notice?.let { notice ->
                            item {
                                Text(
                                    "ℹ️ $notice",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                        if (s.heading != null) {
                            item {
                                Understood(
                                    s,
                                    onEdit = { editing = s.filter },
                                    onSave = { savingFilter = s.filter },
                                )
                            }
                        }
                        if (s.items.isEmpty()) {
                            item {
                                Text(
                                    "ما لقينا نتائج. جرّب تخفف الفلاتر أو تكتب بطريقة ثانية.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        items(s.items, key = { "${it.mediaType}:${it.id}" }) { item ->
                            SearchRow(item) { item.type?.let { onOpen(it, item.id) } }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (s.canLoadMore) {
                            item {
                                TextButton(
                                    onClick = vm::loadMore,
                                    enabled = !vm.loadingMore,
                                    modifier = Modifier.fillMaxWidth().padding(8.dp).focusFrame(),
                                ) { Text(if (vm.loadingMore) "جاري التحميل…" else "اعرض أكثر") }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { initial ->
        FilterSheet(
            initial = initial,
            saved = data.savedFilters,
            onDismiss = { editing = null },
            onApply = {
                editing = null
                vm.applyFilter(it)
            },
            onSave = {
                editing = null
                savingFilter = it
            },
            onDeleteSaved = { name -> scope.launch { container.store.removeFilter(name) } },
        )
    }

    savingFilter?.let { filter ->
        SaveFilterDialog(
            suggestedName = filter.summary().drop(1).take(3).joinToString(" ").ifBlank { filter.summary().first() },
            onDismiss = { savingFilter = null },
            onSave = { name ->
                scope.launch { container.store.saveFilter(SavedFilter(name, filter)) }
                savingFilter = null
                Toast.makeText(context, "حفظنا النمط «$name»", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleHints(onAsk: (String) -> Unit, onVoice: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("اطلب بكلامك العادي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "اكتب أو اضغط المايك وقول وش مزاجك اليوم: النوع، البلد، المنصة، أو شي مشابه لعمل عجبك. " +
                "وتقدر تستخدم «الفلاتر» وتحفظ النمط اللي يعجبك.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(modifier = Modifier.focusFrame(), onClick = onVoice) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null, Modifier.size(18.dp))
            Text("  تكلّم")
        }
        Text("جرّب:", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Examples.forEach { example ->
                SuggestionChip(modifier = Modifier.focusFrame(), onClick = { onAsk(example) }, label = { Text(example) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Understood(s: SearchState.Results, onEdit: () -> Unit, onSave: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.heading.orEmpty(), fontWeight = FontWeight.Bold)
            if (s.chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.chips.forEach { AssistChip(modifier = Modifier.focusFrame(), onClick = onEdit, label = { Text(it) }) }
                }
            }
            if (s.filter != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(modifier = Modifier.focusFrame(), onClick = onEdit) { Text("عدّل") }
                    TextButton(modifier = Modifier.focusFrame(), onClick = onSave) { Text("★ احفظه كنمط") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    initial: DiscoverFilter,
    saved: List<SavedFilter>,
    onDismiss: () -> Unit,
    onApply: (DiscoverFilter) -> Unit,
    onSave: (DiscoverFilter) -> Unit,
    onDeleteSaved: (String) -> Unit,
) {
    var f by remember { mutableStateOf(initial.copy(type = if (initial.type == MediaType.ANIME) MediaType.TV else initial.type)) }
    val thisYear = LocalDate.now().year
    val eras = listOf(
        "الكل" to (null to null),
        "آخر سنتين" to ((thisYear - 1) to null),
        "2020+" to (2020 to null),
        "2010–2019" to (2010 to 2019),
        "2000–2009" to (2000 to 2009),
        "التسعينات" to (1990 to 1999),
        "أقدم" to (null to 1989),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("الفلاتر")

            FilterGroup("النوع") {
                FilterChip(modifier = Modifier.focusFrame(), selected = f.type == MediaType.TV, onClick = { f = f.copy(type = MediaType.TV) }, label = { Text("مسلسلات") })
                FilterChip(modifier = Modifier.focusFrame(), selected = f.type == MediaType.MOVIE, onClick = { f = f.copy(type = MediaType.MOVIE) }, label = { Text("أفلام") })
            }

            FilterGroup("التصنيف (تقدر تختار أكثر من واحد)") {
                Genre.entries.forEach { g ->
                    FilterChip(
                    modifier = Modifier.focusFrame(),
                        selected = g in f.genres,
                        onClick = { f = f.copy(genres = if (g in f.genres) f.genres - g else f.genres + g) },
                        label = { Text(g.label) },
                    )
                }
            }

            FilterGroup("البلد / اللغة") {
                FilterChip(modifier = Modifier.focusFrame(), selected = f.origin == null, onClick = { f = f.copy(origin = null) }, label = { Text("الكل") })
                Origin.entries.forEach { o ->
                    FilterChip(modifier = Modifier.focusFrame(), selected = f.origin == o, onClick = { f = f.copy(origin = o) }, label = { Text(o.label) })
                }
            }

            FilterGroup("المنصة") {
                FilterChip(modifier = Modifier.focusFrame(), selected = f.services.isEmpty(), onClick = { f = f.copy(services = emptySet()) }, label = { Text("أي منصة") })
                StreamingService.entries.forEach { s ->
                    FilterChip(
                    modifier = Modifier.focusFrame(),
                        selected = s.key in f.services,
                        onClick = { f = f.copy(services = if (s.key in f.services) f.services - s.key else f.services + s.key) },
                        label = { Text(s.label) },
                    )
                }
            }

            FilterGroup("الترتيب") {
                SortMode.entries.forEach { mode ->
                    FilterChip(modifier = Modifier.focusFrame(), selected = f.sort == mode, onClick = { f = f.copy(sort = mode) }, label = { Text(mode.label) })
                }
            }

            FilterGroup("الفترة") {
                eras.forEach { (label, range) ->
                    FilterChip(
                    modifier = Modifier.focusFrame(),
                        selected = f.yearFrom == range.first && f.yearTo == range.second,
                        onClick = { f = f.copy(yearFrom = range.first, yearTo = range.second) },
                        label = { Text(label) },
                    )
                }
            }

            FilterGroup("أقل تقييم") {
                listOf(null, 6, 7, 8).forEach { r ->
                    FilterChip(
                    modifier = Modifier.focusFrame(),
                        selected = f.minRating == r,
                        onClick = { f = f.copy(minRating = r) },
                        label = { Text(r?.let { "$it+" } ?: "الكل") },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onApply(f) }, modifier = Modifier.weight(1f).focusFrame()) { Text("اعرض النتائج") }
                OutlinedButton(modifier = Modifier.focusFrame(), onClick = { onSave(f) }) { Text("★ احفظ") }
                TextButton(modifier = Modifier.focusFrame(), onClick = { f = DiscoverFilter(type = f.type) }) { Text("مسح") }
            }

            if (saved.isNotEmpty()) {
                HorizontalDivider()
                Text("أنماطي المحفوظة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                saved.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).clickable { onApply(item.filter) }.padding(vertical = 6.dp),
                        ) {
                            Text("★ ${item.name}", fontWeight = FontWeight.SemiBold)
                            Text(
                                item.filter.summary().joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(modifier = Modifier.focusFrame(), onClick = { onDeleteSaved(item.name) }) {
                            Text("حذف", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun SaveFilterDialog(suggestedName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("احفظ النمط") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("بيطلع لك كزر في البحث، تضغطه وتشوف هذا النوع على طول.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم النمط") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(modifier = Modifier.focusFrame(), enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("حفظ") } },
        dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun SearchRow(item: SearchResult, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(0.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(item.posterPath, width = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (item.type == MediaType.TV) StatusBadge(item.id)
            Text(
                item.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    item.type?.label,
                    item.country?.let { c -> countryLabel(c)?.let { "${countryFlag(c).orEmpty()} $it".trim() } },
                    item.year,
                    ratingLabel(item.voteAverage),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!item.overview.isNullOrBlank()) {
                Text(
                    item.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


/** "في حساباتك": matches for [query] in the user's Xtream accounts (search as you type, after a pause). */
@Composable
private fun XtreamSearchHits(query: String, onOpenSeries: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val accounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    if (accounts.isEmpty()) return
    var hits by remember { mutableStateOf<List<Pair<com.alyoushw.watchhub.data.XtreamAccount, com.alyoushw.watchhub.data.XtreamItem>>>(emptyList()) }
    LaunchedEffect(query, accounts) {
        if (com.alyoushw.watchhub.data.XtreamApi.searchWords(query).length < 2) {
            hits = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(500)
        hits = container.xtream.searchAll(query)
    }
    if (hits.isEmpty()) return
    val many = accounts.size > 1
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("في حساباتك (${hits.size})", Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(hits, key = { (acc, x) -> "${acc.id}:${x.kind}:${x.id}" }) { (acc, x) ->
                val live = x.kind == com.alyoushw.watchhub.data.XtreamKind.LIVE
                Column(
                    Modifier
                        .width(112.dp)
                        .focusHighlight()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            when (x.kind) {
                                com.alyoushw.watchhub.data.XtreamKind.SERIES -> {
                                    container.settings.setActiveXtream(acc.id)
                                    onOpenSeries(x.id)
                                }
                                com.alyoushw.watchhub.data.XtreamKind.LIVE -> com.alyoushw.watchhub.player.PlayerActivity.start(
                                    context, container.xtream.playUrls(x, acc), com.alyoushw.watchhub.data.WatchEntry.live(x, acc),
                                )
                                else -> com.alyoushw.watchhub.player.PlayerActivity.start(
                                    context, container.xtream.playUrls(x, acc), com.alyoushw.watchhub.data.WatchEntry.movie(x, acc),
                                )
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(if (live) 72.dp else 160.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (x.image != null) {
                            coil.compose.AsyncImage(
                                model = x.image,
                                contentDescription = null,
                                contentScale = if (live) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().padding(if (live) 10.dp else 0.dp),
                            )
                        }
                        Text(
                            when (x.kind) {
                                com.alyoushw.watchhub.data.XtreamKind.LIVE -> "قناة"
                                com.alyoushw.watchhub.data.XtreamKind.SERIES -> "مسلسل"
                                else -> "فيلم"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Gold,
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(5.dp))
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f)).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Text(x.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (many) Text(acc.label, style = MaterialTheme.typography.labelSmall, color = Gold, maxLines = 1)
                }
            }
        }
    }
}
