package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.YouTube
import com.alyoushw.watchhub.data.YouTubeException
import com.alyoushw.watchhub.data.YtPlaylist
import com.alyoushw.watchhub.data.YtVideo
import com.alyoushw.watchhub.player.YouTubePlayerActivity
import com.alyoushw.watchhub.player.formatTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class YtTab(val label: String) { FEED("اشتراكاتي"), POPULAR("الرائج"), LIKED("أعجبني"), PLAYLISTS("قوائمي") }

class YouTubeViewModel(private val yt: YouTube) : ViewModel() {
    var videos by mutableStateOf<List<YtVideo>?>(null)
        private set
    var playlists by mutableStateOf<List<YtPlaylist>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var heading by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    fun load(block: suspend () -> List<YtVideo>, title: String? = null) {
        job?.cancel()
        videos = null
        playlists = null
        error = null
        heading = title
        job = viewModelScope.launch {
            try {
                videos = block()
            } catch (e: YouTubeException) {
                error = e.message
            }
        }
    }

    fun loadPlaylists() {
        job?.cancel()
        videos = null
        playlists = null
        error = null
        heading = null
        job = viewModelScope.launch {
            try {
                playlists = yt.playlists()
            } catch (e: YouTubeException) {
                error = e.message
            }
        }
    }
}

/** YouTube with the user's own account: subscriptions, popular, liked, playlists and search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeScreen() {
    val container = appContainer()
    val context = LocalContext.current
    val yt = container.youtube
    val vm: YouTubeViewModel = viewModel { YouTubeViewModel(yt) }
    var tab by remember { mutableStateOf(YtTab.FEED) }
    var query by remember { mutableStateOf("") }
    var loadedFor by remember { mutableStateOf<YtTab?>(null) }

    fun open(t: YtTab, force: Boolean = false) {
        tab = t
        loadedFor = t
        when (t) {
            YtTab.FEED -> vm.load({ yt.subscriptionsFeed(force) })
            YtTab.POPULAR -> vm.load({ yt.popular() })
            YtTab.LIKED -> vm.load({ yt.liked() })
            YtTab.PLAYLISTS -> vm.loadPlaylists()
        }
    }
    LaunchedEffect(Unit) { if (loadedFor == null && vm.videos == null && vm.playlists == null) open(YtTab.FEED) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("يوتيوب") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("ابحث في يوتيوب") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Each search uses a good share of the day's YouTube quota: only on "بحث", not while typing.
                keyboardActions = KeyboardActions(onSearch = {
                    val q = query.trim()
                    if (q.isNotEmpty()) vm.load({ yt.search(q) }, "نتائج «$q»")
                }),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(YtTab.entries) { t ->
                    FilterChip(
                        modifier = Modifier.focusFrame(),
                        selected = tab == t && vm.heading == null,
                        onClick = { open(t, force = tab == t) },
                        label = { Text(t.label) },
                    )
                }
            }
            vm.heading?.let { SectionTitle(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            when {
                vm.error != null -> MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = { open(tab, force = true) })
                vm.playlists != null -> {
                    val lists = vm.playlists.orEmpty()
                    if (lists.isEmpty()) {
                        MessageBox("ما عندك قوائم تشغيل")
                    } else {
                        Grid {
                            items(lists, key = { it.id }) { p ->
                                Thumb(p.thumbnail, p.title, p.count?.let { "$it فيديو" }, null) {
                                    vm.load({ yt.playlist(p.id) }, p.title)
                                }
                            }
                        }
                    }
                }
                vm.videos == null -> CenteredLoading()
                vm.videos.orEmpty().isEmpty() -> MessageBox(if (tab == YtTab.FEED) "ما فيه فيديوهات جديدة من اشتراكاتك" else "ما فيه شي هنا")
                else -> Grid {
                    items(vm.videos.orEmpty().distinctBy { it.id }, key = { it.id }) { v ->
                        Thumb(
                            v.thumbnail,
                            v.title,
                            listOfNotNull(v.channel.takeIf { it.isNotBlank() }, v.publishedAt?.let(::ago)).joinToString(" · "),
                            v.duration?.let { formatTime(it * 1000) },
                        ) { YouTubePlayerActivity.start(context, v.id, v.title) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Grid(content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(260.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

@Composable
private fun Thumb(image: String?, title: String, meta: String?, badge: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().focusHighlight(shape).clip(shape).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (image != null) AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            badge?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 4.dp))
        if (!meta.isNullOrBlank()) {
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

private fun ago(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000
    return when {
        m < 60 -> "قبل ${m.coerceAtLeast(1)} د"
        m < 24 * 60 -> "قبل ${m / 60} س"
        m < 30 * 24 * 60 -> "قبل ${m / (24 * 60)} يوم"
        m < 365 * 24 * 60 -> "قبل ${m / (30 * 24 * 60)} شهر"
        else -> "قبل ${m / (365 * 24 * 60)} سنة"
    }
}
