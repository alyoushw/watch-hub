package com.alyoushw.watchhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.BrowseRequest
import com.alyoushw.watchhub.data.ApiException
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.SearchResult
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

/** Loads every page of a list (TMDB caps a list at 500 pages × 20 titles). */
class BrowseViewModel(private val container: AppContainer, val request: BrowseRequest?) : ViewModel() {
    var items by mutableStateOf<List<SearchResult>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var total by mutableStateOf(0)
        private set

    private var page = 0
    private var totalPages = 1
    val reachedEnd: Boolean get() = page >= totalPages

    init {
        loadMore()
    }

    fun loadMore() {
        val req = request ?: return
        if (loading || reachedEnd) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val response = container.api.page(req.source, page + 1)
                page = response.page.coerceAtLeast(page + 1)
                totalPages = response.totalPages.coerceIn(1, MAX_PAGES)
                total = response.totalResults
                items = (items + response.results.filter { it.posterPath != null })
                    .distinctBy { "${it.mediaType}:${it.id}" }
            } catch (e: ApiException) {
                error = e.message
            }
            loading = false
        }
    }

    private companion object {
        const val MAX_PAGES = 500
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(onBack: () -> Unit, onOpen: (MediaType, Int) -> Unit) {
    val container = appContainer()
    val vm: BrowseViewModel = viewModel { BrowseViewModel(container, container.browse) }
    val grid = rememberLazyGridState()

    // Load the next page when the user gets near the bottom.
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= vm.items.size - 12) vm.loadMore() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(vm.request?.title ?: "عرض الكل", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                vm.request == null -> MessageBox("ارجع للرئيسية واضغط «عرض الكل» مرة ثانية")
                vm.items.isEmpty() && vm.loading -> CenteredLoading()
                vm.items.isEmpty() && vm.error != null -> MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::loadMore)
                vm.items.isEmpty() -> MessageBox("ما فيه أعمال هنا للحين")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    state = grid,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (vm.total > 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                String.format(Locale.US, "%,d", vm.total) + " عمل",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    itemsIndexed(vm.items, key = { _, item -> "${item.mediaType}:${item.id}" }) { index, item ->
                        PosterCard(
                            item,
                            rank = if (vm.request?.ranked == true) index + 1 else null,
                            width = 108.dp,
                        ) { item.type?.let { onOpen(it, item.id) } }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            when {
                                vm.loading -> CircularProgressIndicator()
                                vm.error != null -> TextButton(onClick = vm::loadMore) { Text("صار خطأ، اضغط للمحاولة") }
                                vm.reachedEnd -> Text("هذا كل شي ✓", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> TextButton(onClick = vm::loadMore) { Text("حمّل أكثر") }
                            }
                        }
                    }
                }
            }
        }
    }
}
