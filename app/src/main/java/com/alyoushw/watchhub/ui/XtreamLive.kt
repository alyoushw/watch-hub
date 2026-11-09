package com.alyoushw.watchhub.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.XtreamApi
import com.alyoushw.watchhub.data.XtreamCategory
import com.alyoushw.watchhub.data.XtreamException
import com.alyoushw.watchhub.data.XtreamItem
import com.alyoushw.watchhub.data.XtreamKind
import com.alyoushw.watchhub.data.XtreamProgram
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.data.XtreamAccount
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.QueueItem
import com.alyoushw.watchhub.player.channelKey
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RECENT = "__recent"
private const val ALL = "__all"
private const val FAVORITES = "__fav"

/** A channel and the account it comes from (search runs over every account). */
data class LiveHit(val channel: XtreamItem, val account: XtreamAccount) {
    val key get() = "${account.id}:${channel.id}"
}

/** All live channels of the account, grouped by category, with a now/next guide for the chosen one. */
class LiveViewModel(private val api: XtreamApi) : ViewModel() {
    var categories by mutableStateOf<List<XtreamCategory>>(emptyList())
        private set
    var channels by mutableStateOf<List<XtreamItem>>(emptyList())
        private set
    var counts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var category by mutableStateOf(ALL)
    var selected by mutableStateOf<LiveHit?>(null)
        private set
    var guide by mutableStateOf<List<XtreamProgram>?>(null)
        private set

    /** Every account's channels, loaded the first time the user searches. */
    var everywhere by mutableStateOf<List<LiveHit>?>(null)
        private set
    private var everywhereFor: List<String> = emptyList()

    private var guideJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                categories = api.categories(XtreamKind.LIVE)
                val all = api.all(XtreamKind.LIVE)
                channels = all
                counts = all.groupingBy { it.categoryId.orEmpty() }.eachCount()
            } catch (e: XtreamException) {
                error = e.message
            }
            loading = false
        }
    }

    fun loadEverywhere(accounts: List<XtreamAccount>) {
        val ids = accounts.map { it.id }
        if (ids == everywhereFor && everywhere != null) return
        everywhereFor = ids
        viewModelScope.launch {
            everywhere = coroutineScope {
                accounts.map { acc ->
                    async {
                        try {
                            api.all(XtreamKind.LIVE, acc).map { LiveHit(it, acc) }
                        } catch (e: XtreamException) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
        }
    }

    fun select(hit: LiveHit?) {
        if (hit == null || hit.key == selected?.key) return
        selected = hit
        guide = null
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            delay(350) // Moving down the list with the remote should not fire a request per row.
            guide = try {
                api.shortEpg(hit.channel.id, hit.account)
            } catch (e: XtreamException) {
                emptyList()
            }
        }
    }
}

/**
 * Live channels: categories, channels, and a small live picture of the chosen channel.
 * First tap plays a channel in the small picture; the second (or tapping the picture) opens it
 * full screen, where left/right go through the same list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveChannels(showAccounts: Boolean = true) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by container.settings.xtream.collectAsStateWithLifecycle()
    val accounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    val favorites by container.settings.xtreamFavorites.collectAsStateWithLifecycle()
    val recent by container.settings.xtreamRecent.collectAsStateWithLifecycle()
    val vm: LiveViewModel = viewModel(key = "live:${account.host}:${account.username}") { LiveViewModel(container.xtream) }
    var query by remember { mutableStateOf("") }
    /** The channel playing in the small picture, if any. */
    var previewing by remember { mutableStateOf<LiveHit?>(null) }

    // Leaving the app (or opening the full player) stops the small picture.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) previewing = null }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val complete = remember(accounts) { accounts.filter { it.isComplete } }
    val searching = query.isNotBlank()
    LaunchedEffect(searching, complete) { if (searching && complete.size > 1) vm.loadEverywhere(complete) }

    when {
        vm.loading && vm.channels.isEmpty() -> {
            CenteredLoading()
            return
        }
        vm.error != null && vm.channels.isEmpty() -> {
            MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::load)
            return
        }
    }

    val byId = remember(vm.channels) { vm.channels.associateBy { it.id } }
    val shown: List<LiveHit> = remember(vm.channels, vm.category, favorites, recent, query, vm.everywhere, account) {
        val q = query.trim()
        fun mine(list: List<XtreamItem>) = list.map { LiveHit(it, account) }
        when {
            q.isNotEmpty() -> {
                // Search: every account's channels (the active account first).
                val pool = vm.everywhere?.takeIf { complete.size > 1 } ?: mine(vm.channels)
                val key = channelKey(q)
                pool.filter { it.channel.name.contains(q, ignoreCase = true) || (key.isNotEmpty() && channelKey(it.channel.name).contains(key)) }
                    .sortedBy { if (it.account.id == account.id) 0 else 1 }
            }
            vm.category == ALL -> mine(vm.channels)
            vm.category == FAVORITES -> mine(vm.channels.filter { it.id in favorites })
            vm.category == RECENT -> mine(recent.mapNotNull { byId[it] })
            else -> mine(vm.channels.filter { it.categoryId == vm.category })
        }
    }
    val listState = remember(vm.category, query.isBlank()) { LazyListState() }
    val multi = complete.size > 1

    fun openFull(hit: LiveHit) {
        container.settings.addXtreamRecent(hit.channel.id)
        val list = shown.ifEmpty { listOf(hit) }
        val items = list.mapIndexed { i, h ->
            QueueItem(container.xtream.playUrls(h.channel, h.account), WatchEntry.live(h.channel, h.account), h.channel.num ?: (i + 1), h.channel)
        }
        val index = list.indexOfFirst { it.key == hit.key }.coerceAtLeast(0)
        // One connection at a time: the small picture lets go before the full player starts.
        val wasPreviewing = previewing != null
        previewing = null
        scope.launch {
            if (wasPreviewing) delay(400)
            PlayerActivity.start(context, items, index)
        }
    }

    fun tap(hit: LiveHit) {
        if (previewing?.key == hit.key) {
            openFull(hit)
        } else {
            vm.select(hit)
            previewing = hit
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val channelList: @Composable (Modifier) -> Unit = { mod ->
            Pane(mod) {
                when {
                    searching && multi && vm.everywhere == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("ندوّر في كل حساباتك…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                searching -> "ما لقينا قناة بهالاسم"
                                vm.category == FAVORITES -> "اضغط مطولاً على أي قناة عشان تضيفها للمفضلة"
                                else -> "ما فيه قنوات هنا"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(state = listState, contentPadding = PaddingValues(6.dp)) {
                        itemsIndexed(shown, key = { _, h -> h.key }) { index, h ->
                            ChannelRow(
                                number = h.channel.num ?: (index + 1),
                                channel = h.channel,
                                accountLabel = if (multi && searching) h.account.label else null,
                                selected = vm.selected?.key == h.key,
                                playing = previewing?.key == h.key,
                                favorite = h.account.id == account.id && h.channel.id in favorites,
                                onFocus = { if (wide && previewing == null) vm.select(h) },
                                onClick = { tap(h) },
                                onLongClick = {
                                    if (h.account.id != account.id) return@ChannelRow
                                    container.settings.toggleXtreamFavorite(h.channel.id)
                                    val added = h.channel.id !in favorites
                                    Toast.makeText(context, if (added) "أُضيفت للمفضلة" else "انشالت من المفضلة", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                }
            }
        }
        val search: @Composable (Modifier) -> Unit = { mod ->
            PillSearch(query, { query = it }, if (multi) "ابحث عن قناة في كل الحسابات" else "البحث في القنوات", mod)
        }

        if (wide) {
            // Big screens: categories | channels | live picture and guide, edge to edge.
            Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val hit = previewing ?: vm.selected
                    LivePicture(
                        hit = previewing,
                        idle = hit,
                        onOpen = { (previewing ?: vm.selected)?.let(::openFull) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                    Pane(Modifier.fillMaxWidth().weight(1f)) {
                        if (hit == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("اختر قناة: ضغطة تشغّلها هنا، والثانية تفتحها كاملة", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            ChannelInfo(
                                hit = hit,
                                accountLabel = if (multi) hit.account.label else null,
                                categoryName = vm.categories.firstOrNull { it.id == hit.channel.categoryId }?.name.takeIf { hit.account.id == account.id },
                                guide = vm.guide.takeIf { vm.selected?.key == hit.key },
                                favorite = hit.account.id == account.id && hit.channel.id in favorites,
                                onPlay = { openFull(hit) },
                                onFavorite = { if (hit.account.id == account.id) container.settings.toggleXtreamFavorite(hit.channel.id) },
                            )
                        }
                    }
                }
                Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    search(Modifier.fillMaxWidth())
                    if (showAccounts) AccountSwitcher()
                    channelList(Modifier.fillMaxWidth().weight(1f))
                }
                CategoryPane(
                    categories = vm.categories,
                    counts = vm.counts,
                    total = vm.channels.size,
                    favorites = favorites.count { it in byId },
                    recent = recent.count { it in byId },
                    selected = if (query.isBlank()) vm.category else null,
                    onSelect = {
                        query = ""
                        vm.category = it
                    },
                    modifier = Modifier.width(230.dp).fillMaxHeight(),
                )
            }
        } else {
            // Phones: the live picture on top while a channel plays, categories as chips, the list below.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                previewing?.let { hit ->
                    Column {
                        LivePicture(hit = hit, idle = hit, onOpen = { openFull(hit) }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), rounded = false)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(hit.channel.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val now = vm.guide?.firstOrNull()?.title.takeIf { vm.selected?.key == hit.key }
                                Text(
                                    listOfNotNull(now?.let { "الحين: $it" }, if (multi) hit.account.label else null).joinToString(" · ").ifEmpty { "اضغط مرة ثانية عشان تفتحها كاملة" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                onClick = { openFull(hit) },
                                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206)),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) { Text("ملء الشاشة", fontWeight = FontWeight.Bold) }
                            TextButton(onClick = { previewing = null }) { Text("إيقاف") }
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showAccounts) AccountSwitcher()
                    search(Modifier.fillMaxWidth())
                    if (!searching) {
                        CategoryChips(
                            categories = vm.categories,
                            favorites = favorites.count { it in byId },
                            recent = recent.count { it in byId },
                            selected = vm.category,
                            onSelect = { vm.category = it },
                        )
                    }
                    channelList(Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp))
                }
            }
        }
    }
}

/** Categories as one scrolling row of chips (phones). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChips(categories: List<XtreamCategory>, favorites: Int, recent: Int, selected: String, onSelect: (String) -> Unit) {
    val fixed = buildList {
        add(ALL to "الكل")
        if (favorites > 0) add(FAVORITES to "المفضلة")
        if (recent > 0) add(RECENT to "شوهد مؤخراً")
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(fixed, key = { it.first }) { (id, name) ->
            FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(name, maxLines = 1) }, modifier = Modifier.focusFrame())
        }
        items(categories, key = { it.id }) { c ->
            FilterChip(selected = selected == c.id, onClick = { onSelect(c.id) }, label = { Text(c.name, maxLines = 1) }, modifier = Modifier.focusFrame())
        }
    }
}

/**
 * The small live picture. [hit] plays (null: shows [idle]'s logo). Tapping it opens the
 * channel full screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LivePicture(hit: LiveHit?, idle: LiveHit?, onOpen: () -> Unit, modifier: Modifier, rounded: Boolean = true) {
    val context = LocalContext.current
    val container = appContainer()
    val player = remember {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
    }
    DisposableEffect(player) { onDispose { player.release() } }
    var buffering by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf<String?>(null) }

    val urls = remember(hit?.key) { hit?.let { container.xtream.playUrls(it.channel, it.account) }.orEmpty() }
    /** Links that failed for this channel; the next one is tried. */
    var errors by remember { mutableIntStateOf(0) }

    fun start(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                errors++
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                quality = videoSize.height.takeIf { it > 0 }?.let { h -> if (h >= 2100) "4K" else "${minOf(h, videoSize.width)}p" }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(hit?.key) {
        errors = 0
        failed = false
        quality = null
        if (urls.isEmpty()) {
            player.stop()
            player.clearMediaItems()
        } else {
            start(urls[0])
        }
    }
    LaunchedEffect(errors) {
        if (errors == 0) return@LaunchedEffect
        if (errors < urls.size) start(urls[errors]) else failed = true
    }

    val shape = if (rounded) RoundedCornerShape(14.dp) else RoundedCornerShape(0.dp)
    Box(
        modifier
            .clip(shape)
            .background(Brush.radialGradient(listOf(Gold.copy(alpha = 0.30f), Color(0xFF0B0907)))),
        contentAlignment = Alignment.Center,
    ) {
        if (hit != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (failed) Text("ما اشتغلت القناة", color = Color.White)
            else if (buffering) CircularProgressIndicator(Modifier.size(36.dp), color = Gold, strokeWidth = 3.dp)
        } else {
            val img = idle?.channel?.image
            if (img != null) {
                AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(32.dp))
            } else {
                Text("📺", fontSize = 48.sp)
            }
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "● مباشر",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE5484D)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (hit != null) quality?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        // Over the video view, which would otherwise take the touches.
        Box(Modifier.matchParentSize().focusFrame(shape).clickable(onClick = onOpen))
        if (hit != null) {
            Text(
                "اضغط للشاشة الكاملة",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Pane(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) { content() }
}

@Composable
private fun PillSearch(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(Gold),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CategoryPane(
    categories: List<XtreamCategory>,
    counts: Map<String, Int>,
    total: Int,
    favorites: Int,
    recent: Int,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    var filter by remember { mutableStateOf("") }
    val fixed = listOf(
        Triple(RECENT, "شوهد مؤخراً", recent),
        Triple(ALL, "الكل", total),
        Triple(FAVORITES, "المفضلة", favorites),
    )
    val rest = remember(categories, filter) {
        val q = filter.trim()
        categories.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    }
    Pane(modifier) {
        Column {
            PillSearch(filter, { filter = it }, "البحث في الفئات", Modifier.fillMaxWidth().padding(8.dp))
            LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
                if (filter.isBlank()) {
                    items(fixed, key = { it.first }) { (id, name, count) ->
                        CategoryRow(name, count, selected == id) { onSelect(id) }
                    }
                }
                items(rest, key = { it.id }) { c ->
                    CategoryRow(c.name, counts[c.id] ?: 0, selected == c.id) { onSelect(c.id) }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusFrame(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Gold.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) Gold else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    number: Int,
    channel: XtreamItem,
    accountLabel: String?,
    selected: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusFrame(shape)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            number.toString(),
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Box(
            Modifier.size(width = 54.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E1B18)),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.image != null) {
                AsyncImage(
                    model = channel.image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            } else {
                Text("📺", fontSize = 16.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (playing) Gold else MaterialTheme.colorScheme.onSurface,
            )
            accountLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Gold, maxLines = 1) }
        }
        if (playing) Text("▶", color = Gold, fontWeight = FontWeight.Bold)
        if (favorite) Icon(Icons.Filled.Star, contentDescription = "مفضلة", tint = Gold, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ChannelInfo(
    hit: LiveHit,
    accountLabel: String?,
    categoryName: String?,
    guide: List<XtreamProgram>?,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    val channel = hit.channel
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(channel.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(categoryName, accountLabel).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        when {
            guide == null -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            guide.isEmpty() -> Text("ما فيه دليل برامج لهذي القناة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                guide.take(3).forEachIndexed { i, p ->
                    val time = listOfNotNull(p.start?.let { XtreamApi.formatTime(it) }, p.end?.let { XtreamApi.formatTime(it) }).joinToString(" - ")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (i == 0) "الحين" else "بعده",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (i == 0) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (time.isNotEmpty()) Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206)),
                modifier = Modifier.weight(1f).height(48.dp).focusFrame(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("ملء الشاشة", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onFavorite, modifier = Modifier.height(48.dp).focusFrame()) {
                Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null, tint = Gold)
                Spacer(Modifier.width(6.dp))
                Text("المفضلة")
            }
        }
    }
}
