package com.alyoushw.watchhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.SearchResult
import com.alyoushw.watchhub.data.TmdbException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val items: List<SearchResult>) : SearchState
    data class Failed(val message: String) : SearchState
}

class SearchViewModel(private val container: AppContainer) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var state by mutableStateOf<SearchState>(SearchState.Idle)
        private set
    private var job: Job? = null

    fun onQueryChange(value: String) {
        query = value
        job?.cancel()
        if (value.isBlank()) {
            state = SearchState.Idle
            return
        }
        job = viewModelScope.launch {
            delay(400) // wait for the user to stop typing
            run(value)
        }
    }

    fun submit() {
        job?.cancel()
        if (query.isNotBlank()) job = viewModelScope.launch { run(query) }
    }

    private suspend fun run(value: String) {
        state = SearchState.Loading
        state = try {
            SearchState.Results(container.api.search(value.trim()))
        } catch (e: TmdbException) {
            SearchState.Failed(e.message ?: "صار خطأ")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (MediaType, Int) -> Unit, onOpenSettings: () -> Unit) {
    val container = appContainer()
    val vm: SearchViewModel = viewModel { SearchViewModel(container) }
    val apiKey by container.settings.apiKey.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("وش تبي تشوف؟") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("فيلم، مسلسل، أنمي…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "مسح")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.submit() }),
            )

            when {
                apiKey.isBlank() -> MessageBox(
                    title = "خطوة وحدة قبل نبدأ",
                    body = "التطبيق يجيب بيانات الأفلام والمسلسلات ووين متوفرة من TMDB. تحتاج مفتاح مجاني تحطه في الإعدادات.",
                    actionLabel = "افتح الإعدادات",
                    onAction = onOpenSettings,
                )

                else -> when (val s = vm.state) {
                    SearchState.Idle -> MessageBox(
                        title = "ابحث مرة وحدة في كل المنصات",
                        body = "اكتب اسم أي عمل وبنقولك هل هو على نتفلكس أو برايم فيديو أو كرنشي رول، ونفتحه لك مباشرة.",
                    )

                    SearchState.Loading -> CenteredLoading()
                    is SearchState.Failed -> MessageBox(s.message, actionLabel = "حاول مرة ثانية", onAction = vm::submit)
                    is SearchState.Results ->
                        if (s.items.isEmpty()) {
                            MessageBox("ما لقينا شي بهذا الاسم", "جرّب تكتبه بالإنجليزي.")
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                                items(s.items, key = { "${it.mediaType}:${it.id}" }) { item ->
                                    SearchRow(item) { item.type?.let { onOpen(it, item.id) } }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(item: SearchResult, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(item.posterPath, width = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val kind = if (item.type == MediaType.TV) "مسلسل" else "فيلم"
            Text(
                listOfNotNull(kind, item.year).joinToString(" · "),
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
