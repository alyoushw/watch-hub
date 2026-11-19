package com.alyoushw.watchhub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alyoushw.watchhub.data.FourU
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.data.XtreamAccount
import com.alyoushw.watchhub.data.XtreamApi
import com.alyoushw.watchhub.data.XtreamItem
import com.alyoushw.watchhub.data.XtreamKind
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.QueueItem
import com.alyoushw.watchhub.player.channelKey
import com.alyoushw.watchhub.player.qualityTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A channel unified across servers: its base key, a display name, and every (server, quality) copy. */
private data class FolderChannel(val key: String, val title: String, val hits: List<LiveHit>)

/** A brand folder inside a section (e.g. "ألوان"), with its channels. */
private data class FolderGroup(val label: String, val channels: List<FolderChannel>)

/** section ("رياضة") -> ordered brand groups. */
private typealias LiveTree = Map<String, List<FolderGroup>>

private val SECTION_ORDER = listOf("رياضة", "أخبار", "أفلام", "مسلسلات", "أطفال", "دينية", "موسيقى", "وثائقي")
private val QUALITY_ORDER = listOf("8k", "4k", "fhd", "hd", "sd", "hevc", "raw", "50fps", "60fps", "low")

/** Brand folders shown in this order wherever they appear; others follow, alphabetically. */
private val GROUP_ORDER = listOf("bein", "bn", "الوان", "alwan", "8", "سولو", "solo", "ssc", "osn", "mbc", "روتانا", "rotana", "تود", "tod")

/** Words stripped from a category name so "AR ألوان", "ألوان VIP"… all fold into one brand "ألوان". */
private val GROUP_DECORATORS = setOf(
    "ar", "en", "eng", "عربي", "عربيه", "عربيات", "vip", "hd", "fhd", "uhd", "sd", "4k", "8k",
    "hevc", "raw", "h265", "50fps", "60fps", "fps", "tv", "plus", "backup", "new",
)

private fun normKey(s: String): String =
    s.lowercase().replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')

/** The brand name of a category, with its prefixes/suffixes (AR, VIP, HD…) removed. */
private fun brandLabel(category: String): String {
    val base = category.replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ").replace("|", " ")
    val tokens = base.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
    val kept = tokens.filter { normKey(it) !in GROUP_DECORATORS }
    return kept.joinToString(" ").trim().ifBlank { category.trim() }
}

private fun groupKeyOf(label: String): String = normKey(label).replace(" ", "")

private fun groupPriority(key: String): Int =
    GROUP_ORDER.indexOfFirst { key.contains(it) }.let { if (it < 0) 500 else it }

/** A sort key that orders numbers naturally ("beIN 2" before "beIN 10"). */
private fun natKey(s: String): String = Regex("\\d+").replace(s) { it.value.padStart(6, '0') }

/** A readable channel name without brackets or quality words. */
private fun displayName(name: String): String = name
    .replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ")
    .replace(Regex("(?i)\\b(4k|uhd|8k|fhd|hd|sd|raw|hevc|h265|50fps|60fps|low)\\b"), " ")
    .replace(Regex("^\\s*[A-Za-z]{2,3}\\s*[|:]\\s*"), "")
    .replace(Regex("\\s+"), " ").trim().ifBlank { name.trim() }

/** A single label for a channel's quality: its tags in order, or "عادي" when it has none. */
private fun qualityLabel(name: String): String {
    val tags = qualityTags(name)
    if (tags.isEmpty()) return "عادي"
    return tags.sortedBy { QUALITY_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
        .joinToString(" ") { it.uppercase() }
}

private class FolderLiveViewModel(
    private val api: XtreamApi,
    private val accounts: List<XtreamAccount>,
) : ViewModel() {
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var tree by mutableStateOf<LiveTree>(emptyMap())
        private set

    /** Every channel by key, merged across all sections/groups — for the favourites view. */
    var allChannels by mutableStateOf<Map<String, FolderChannel>>(emptyMap())
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val usable = accounts.filter { it.isComplete }
                if (usable.isEmpty()) { tree = emptyMap(); loading = false; return@launch }
                val loaded = coroutineScope {
                    usable.map { acc ->
                        async {
                            val cats = runCatching { api.categories(XtreamKind.LIVE, acc) }.getOrDefault(emptyList())
                                .associate { it.id to it.name }
                            val chans = runCatching { api.all(XtreamKind.LIVE, acc) }.getOrDefault(emptyList())
                            Triple(acc, cats, chans)
                        }
                    }.awaitAll()
                }
                val built = withContext(Dispatchers.Default) { buildTree(loaded) }
                tree = built.first
                allChannels = built.second
                if (tree.isEmpty()) error = "ما وصلت قنوات من الحسابات، تأكد من الاشتراك والإنترنت"
            } catch (e: Exception) {
                error = e.message ?: "تعذّر تحميل القنوات"
            }
            loading = false
        }
    }

    private fun channelTitle(hits: List<LiveHit>): String =
        hits.map { displayName(it.channel.name) }.filter { it.isNotBlank() }.minByOrNull { it.length }
            ?: hits.first().channel.name

    private fun buildTree(
        loaded: List<Triple<XtreamAccount, Map<String, String>, List<XtreamItem>>>,
    ): Pair<LiveTree, Map<String, FolderChannel>> {
        data class GroupAcc(val label: String, val channels: LinkedHashMap<String, MutableList<LiveHit>>)
        // section -> groupKey -> GroupAcc
        val sections = linkedMapOf<String, LinkedHashMap<String, GroupAcc>>()
        val all = linkedMapOf<String, MutableList<LiveHit>>()
        for ((acc, cats, chans) in loaded) {
            for (ch in chans) {
                val catName = ch.categoryId?.let { cats[it] }
                val section = suggestFolders(ch.name, catName).firstOrNull() ?: "أخرى"
                val label = brandLabel(catName?.takeIf { it.isNotBlank() } ?: ch.name)
                val gkey = groupKeyOf(label)
                val key = channelKey(ch.name).ifBlank { normKey(ch.name).trim() }
                val hit = LiveHit(ch, acc)
                sections.getOrPut(section) { linkedMapOf() }
                    .getOrPut(gkey) { GroupAcc(label, linkedMapOf()) }
                    .channels.getOrPut(key) { mutableListOf() }.add(hit)
                all.getOrPut(key) { mutableListOf() }.add(hit)
            }
        }
        val orderedSections = sections.keys.sortedBy { SECTION_ORDER.indexOf(it).let { i -> if (i < 0) 90 else i } }
        val tree = linkedMapOf<String, List<FolderGroup>>()
        for (sec in orderedSections) {
            val groups = sections[sec] ?: continue
            val list = groups.entries
                .sortedWith(compareBy({ groupPriority(it.key) }, { it.value.label }))
                .map { (_, acc) ->
                    val channels = acc.channels.map { (key, hits) -> FolderChannel(key, channelTitle(hits), hits.sortedBy { it.channel.name }) }
                        .sortedBy { natKey(it.title) }
                    FolderGroup(acc.label, channels)
                }
            tree[sec] = list
        }
        val allChannels = all.mapValues { (key, hits) -> FolderChannel(key, channelTitle(hits), hits.sortedBy { it.channel.name }) }
        return tree to allChannels
    }
}

/** A colour per server so every quality shows which server it is at a glance. */
private val SERVER_COLORS = listOf(
    Color(0xFF4BB3D4), Color(0xFF57C98A), Color(0xFFB38BF0),
    Color(0xFFE0863C), Color(0xFFE5894D), Color(0xFF8AA4FF), Color(0xFFD98AC0),
)

/** Where we are in the folder tree. */
private sealed interface Level {
    data object Root : Level
    data object Favorites : Level
    data class Section(val name: String) : Level
    data class Group(val section: String, val group: FolderGroup) : Level
    data class Channel(val channel: FolderChannel) : Level
    data class Quality(val channel: FolderChannel, val quality: String) : Level
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderLiveScreen() {
    val container = appContainer()
    val context = LocalContext.current
    val savedAccounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    val favorites by container.settings.folderFavorites.collectAsStateWithLifecycle()
    val accounts = remember(savedAccounts) { savedAccounts.filter { it.isComplete } + FourU.account }
    val colorOf = remember(accounts) { accounts.mapIndexed { i, a -> a.id to SERVER_COLORS[i % SERVER_COLORS.size] }.toMap() }
    val vm: FolderLiveViewModel = viewModel(key = "folderlive:${accounts.joinToString { it.id }}") {
        FolderLiveViewModel(container.xtream, accounts)
    }

    var stack by remember { mutableStateOf<List<Level>>(listOf(Level.Root)) }
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

    if (vm.loading && vm.tree.isEmpty()) { CenteredLoading(); return }
    if (vm.error != null && vm.tree.isEmpty()) { MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::load); return }

    val accent = MaterialTheme.colorScheme.primary
    val tree = vm.tree
    val cur = stack.last()
    fun push(l: Level) { stack = stack + l }

    fun play(hit: LiveHit, siblings: List<LiveHit>) {
        container.settings.addXtreamRecent(hit.channel.id)
        val items = siblings.map { h ->
            QueueItem(
                container.xtream.playUrls(h.channel, h.account),
                WatchEntry.live(h.channel, h.account),
                h.channel.num ?: 1,
                h.channel,
                httpHeaders = if (h.account.id == FourU.account.id) FourU.channelHeaders(h.channel.id) else emptyMap(),
            )
        }
        PlayerActivity.start(context, items, siblings.indexOf(hit).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        // Breadcrumb + back
        val crumbs = buildList {
            add("المباشر")
            stack.drop(1).forEach {
                add(
                    when (it) {
                        Level.Favorites -> "المفضلة"
                        is Level.Section -> it.name
                        is Level.Group -> it.group.label
                        is Level.Channel -> it.channel.title
                        is Level.Quality -> it.quality
                        Level.Root -> ""
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stack.size > 1) {
                TextButton(onClick = { stack = stack.dropLast(1) }, modifier = Modifier.focusFrame()) { Text("‹ رجوع", color = accent) }
                Spacer(Modifier.width(8.dp))
            }
            Text(crumbs.joinToString("  ‹  "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val prompt = when (cur) {
            Level.Root -> "اختر القسم"
            Level.Favorites -> "المفضلة"
            is Level.Section -> "اختر المجموعة"
            is Level.Group -> "اختر القناة"
            is Level.Channel -> "اختر الجودة"
            is Level.Quality -> "اختر السيرفر"
        }
        Text(prompt, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)

        LazyColumn(
            Modifier.fillMaxSize().readableWidth(900.dp),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (cur) {
                Level.Root -> {
                    if (favorites.isNotEmpty()) item {
                        FolderRow("⭐ المفضلة", "${favorites.size} قناة") { push(Level.Favorites) }
                    }
                    items(tree.keys.toList()) { sec ->
                        FolderRow(sec, "${tree[sec]?.size ?: 0} مجموعة") { push(Level.Section(sec)) }
                    }
                }
                Level.Favorites -> {
                    val favChannels = vm.allChannels.values.filter { it.key in favorites }.sortedBy { natKey(it.title) }
                    if (favChannels.isEmpty()) item { Text("لا توجد قنوات مفضّلة بعد. اضغط مطولاً على أي قناة لإضافتها.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(favChannels) { ch ->
                        ChannelRow(ch, colorOf, favorite = true, onClick = { push(Level.Channel(ch)) }, onLong = { container.settings.toggleFolderFavorite(ch.key) })
                    }
                }
                is Level.Section -> items(tree[cur.name].orEmpty()) { grp ->
                    FolderRow(grp.label, "${grp.channels.size} قناة") { push(Level.Group(cur.name, grp)) }
                }
                is Level.Group -> items(cur.group.channels) { ch ->
                    ChannelRow(ch, colorOf, favorite = ch.key in favorites, onClick = { push(Level.Channel(ch)) }, onLong = { container.settings.toggleFolderFavorite(ch.key) })
                }
                is Level.Channel -> {
                    val byQuality = cur.channel.hits.groupBy { qualityLabel(it.channel.name) }.entries
                        .sortedBy { QUALITY_ORDER.indexOf(it.key.lowercase().substringBefore(' ')).let { i -> if (i < 0) 99 else i } }
                    items(byQuality) { (qual, hits) ->
                        QualityRow(qual, hits, colorOf, accent) { push(Level.Quality(cur.channel, qual)) }
                    }
                }
                is Level.Quality -> {
                    val hits = cur.channel.hits.filter { qualityLabel(it.channel.name) == cur.quality }
                    items(hits) { hit ->
                        ServerRow(cur.quality, hit, colorOf[hit.account.id] ?: accent, accent) { play(hit, hits) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowCard(onClick: () -> Unit, onLong: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .focusFrame(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp), content = content)
    }
}

@Composable
private fun FolderRow(title: String, subtitle: String, onClick: () -> Unit) {
    RowCard(onClick) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Text("‹", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ServerDots(hits: List<LiveHit>, colorOf: Map<String, Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        hits.map { it.account.id }.distinct().take(5).forEach { id ->
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(colorOf[id] ?: Color.Gray))
        }
    }
}

@Composable
private fun ChannelRow(ch: FolderChannel, colorOf: Map<String, Color>, favorite: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val servers = ch.hits.map { it.account.id }.distinct().size
    val quals = ch.hits.map { qualityLabel(it.channel.name) }.distinct().size
    RowCard(onClick, onLong) {
        if (favorite) Text("⭐", fontSize = 13.sp)
        Column(Modifier.weight(1f)) {
            Text(ch.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("$quals جودة · $servers سيرفر", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ServerDots(ch.hits, colorOf)
            }
        }
        Text("‹", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QualityBadge(text: String, accent: Color) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(accent).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun ServerTag(name: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(name, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun QualityRow(quality: String, hits: List<LiveHit>, colorOf: Map<String, Color>, accent: Color, onClick: () -> Unit) {
    RowCard(onClick) {
        QualityBadge(quality, accent)
        Column(Modifier.weight(1f)) {
            Text("جودة $quality", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("${hits.map { it.account.id }.distinct().size} سيرفر", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ServerDots(hits, colorOf)
            }
        }
        Text("‹", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ServerRow(quality: String, hit: LiveHit, color: Color, accent: Color, onClick: () -> Unit) {
    RowCard(onClick) {
        QualityBadge(quality, accent)
        Column(Modifier.weight(1f)) { ServerTag(hit.account.label, color) }
        Text("▶", color = accent)
    }
}
