package com.alyoushw.watchhub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.alyoushw.watchhub.data.FourU
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.data.XtreamAccount
import com.alyoushw.watchhub.data.XtreamApi
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
private data class FolderChannel(val key: String, val title: String, val hits: List<com.alyoushw.watchhub.ui.LiveHit>)

/** قسم ▸ مجموعة ▸ list of channels. */
private typealias LiveTree = Map<String, Map<String, List<FolderChannel>>>

private val SECTION_ORDER = listOf("رياضة", "أخبار", "أفلام", "مسلسلات", "أطفال", "دينية", "موسيقى", "وثائقي")
private val QUALITY_ORDER = listOf("8k", "4k", "fhd", "hd", "sd", "hevc", "raw", "50fps", "60fps", "low")

/** A readable channel name without brackets or quality words: "AR| beIN 1 4K ᴿᴬᵂ" → "beIN 1". */
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
                tree = withContext(Dispatchers.Default) { buildTree(loaded) }
                if (tree.isEmpty()) error = "ما وصلت قنوات من الحسابات، تأكد من الاشتراك والإنترنت"
            } catch (e: Exception) {
                error = e.message ?: "تعذّر تحميل القنوات"
            }
            loading = false
        }
    }

    private fun buildTree(loaded: List<Triple<XtreamAccount, Map<String, String>, List<com.alyoushw.watchhub.data.XtreamItem>>>): LiveTree {
        // قسم -> مجموعة -> channelKey -> hits
        val sections = linkedMapOf<String, LinkedHashMap<String, LinkedHashMap<String, MutableList<com.alyoushw.watchhub.ui.LiveHit>>>>()
        for ((acc, cats, chans) in loaded) {
            for (ch in chans) {
                val catName = ch.categoryId?.let { cats[it] }
                val section = suggestFolders(ch.name, catName).firstOrNull() ?: "أخرى"
                val group = catName?.takeIf { it.isNotBlank() } ?: "غير مصنّف"
                val key = channelKey(ch.name).ifBlank { ch.name.lowercase().trim() }
                sections.getOrPut(section) { linkedMapOf() }
                    .getOrPut(group) { linkedMapOf() }
                    .getOrPut(key) { mutableListOf() }
                    .add(com.alyoushw.watchhub.ui.LiveHit(ch, acc))
            }
        }
        // Order sections, then build channel nodes (pick the cleanest display name).
        val orderedSections = sections.keys.sortedBy { SECTION_ORDER.indexOf(it).let { i -> if (i < 0) 90 + it.hashCode().and(0xF) else i } }
        val out = linkedMapOf<String, Map<String, List<FolderChannel>>>()
        for (sec in orderedSections) {
            val groups = sections[sec] ?: continue
            val outGroups = linkedMapOf<String, List<FolderChannel>>()
            for ((group, chans) in groups.entries.sortedBy { it.key }) {
                val nodes = chans.map { (key, hits) ->
                    val title = hits.map { displayName(it.channel.name) }.filter { it.isNotBlank() }
                        .minByOrNull { it.length } ?: hits.first().channel.name
                    FolderChannel(key, title, hits.sortedBy { it.channel.name })
                }.sortedBy { it.title }
                outGroups[group] = nodes
            }
            out[sec] = outGroups
        }
        return out
    }
}

/** A colour per server so every quality shows which server it is at a glance. */
private val SERVER_COLORS = listOf(
    Color(0xFF4BB3D4), Color(0xFF57C98A), Color(0xFFB38BF0),
    Color(0xFFE0863C), Color(0xFFE5894D), Color(0xFF8AA4FF), Color(0xFFD98AC0),
)

@Composable
fun FolderLiveScreen() {
    val container = appContainer()
    val context = LocalContext.current
    val savedAccounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    // Every provider the user added, plus the built-in free 4U source.
    val accounts = remember(savedAccounts) { savedAccounts.filter { it.isComplete } + FourU.account }
    val colorOf = remember(accounts) { accounts.mapIndexed { i, a -> a.id to SERVER_COLORS[i % SERVER_COLORS.size] }.toMap() }
    val vm: FolderLiveViewModel = viewModel(key = "folderlive:${accounts.joinToString { it.id }}") {
        FolderLiveViewModel(container.xtream, accounts)
    }

    // Navigation path: [] قسم · [sec] مجموعة · [sec,grp] قناة · [sec,grp,key] جودة · [sec,grp,key,qual] سيرفر
    var path by remember { mutableStateOf<List<String>>(emptyList()) }
    BackHandler(enabled = path.isNotEmpty()) { path = path.dropLast(1) }

    if (vm.loading && vm.tree.isEmpty()) { CenteredLoading(); return }
    if (vm.error != null && vm.tree.isEmpty()) { MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::load); return }

    val accent = MaterialTheme.colorScheme.primary
    val tree = vm.tree

    fun channelsOf(sec: String, grp: String): List<FolderChannel> = tree[sec]?.get(grp).orEmpty()
    fun channelOf(sec: String, grp: String, key: String): FolderChannel? = channelsOf(sec, grp).firstOrNull { it.key == key }

    fun play(hit: com.alyoushw.watchhub.ui.LiveHit, siblings: List<com.alyoushw.watchhub.ui.LiveHit>) {
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
        val crumbs = listOf("المباشر") + path.mapIndexed { i, p ->
            when (i) {
                2 -> channelOf(path[0], path[1], p)?.title ?: p
                3 -> p
                else -> p
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path.isNotEmpty()) {
                TextButton(onClick = { path = path.dropLast(1) }, modifier = Modifier.focusFrame()) { Text("‹ رجوع", color = accent) }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                crumbs.joinToString("  ‹  "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val prompt = when (path.size) {
            0 -> "اختر القسم"; 1 -> "اختر المجموعة"; 2 -> "اختر القناة"; 3 -> "اختر الجودة"; else -> "اختر السيرفر"
        }
        Text(prompt, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)

        LazyColumn(
            Modifier.fillMaxSize().readableWidth(900.dp),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (path.size) {
                0 -> items(tree.keys.toList()) { sec ->
                    FolderRow(sec, "${tree[sec]?.size ?: 0} مجموعة") { path = listOf(sec) }
                }
                1 -> {
                    val groups = tree[path[0]].orEmpty()
                    items(groups.keys.toList()) { grp ->
                        FolderRow(grp, "${groups[grp]?.size ?: 0} قناة") { path = path + grp }
                    }
                }
                2 -> items(channelsOf(path[0], path[1])) { ch ->
                    val servers = ch.hits.map { it.account.id }.distinct().size
                    val quals = ch.hits.map { qualityLabel(it.channel.name) }.distinct().size
                    ChannelRow(ch.title, "$quals جودة · $servers سيرفر", ch.hits, colorOf) { path = path + ch.key }
                }
                3 -> {
                    val ch = channelOf(path[0], path[1], path[2])
                    val byQuality = ch?.hits?.groupBy { qualityLabel(it.channel.name) }
                        ?.entries?.sortedBy { QUALITY_ORDER.indexOf(it.key.lowercase().substringBefore(' ')).let { i -> if (i < 0) 99 else i } }
                        .orEmpty()
                    items(byQuality) { (qual, hits) ->
                        QualityRow(qual, hits, colorOf, accent) { path = path + qual }
                    }
                }
                else -> {
                    val ch = channelOf(path[0], path[1], path[2])
                    val hits = ch?.hits?.filter { qualityLabel(it.channel.name) == path[3] }.orEmpty()
                    items(hits) { hit ->
                        ServerRow(path[3], hit, colorOf[hit.account.id] ?: accent, accent) { play(hit, hits) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowCard(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .focusFrame(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        content = content,
    )
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
private fun ServerDots(hits: List<com.alyoushw.watchhub.ui.LiveHit>, colorOf: Map<String, Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        hits.map { it.account.id }.distinct().take(5).forEach { id ->
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(colorOf[id] ?: Color.Gray))
        }
    }
}

@Composable
private fun ChannelRow(title: String, subtitle: String, hits: List<com.alyoushw.watchhub.ui.LiveHit>, colorOf: Map<String, Color>, onClick: () -> Unit) {
    RowCard(onClick) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ServerDots(hits, colorOf)
            }
        }
        Text("‹", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QualityBadge(text: String, accent: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).background(accent).padding(horizontal = 9.dp, vertical = 3.dp),
    ) { Text(text, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

@Composable
private fun ServerTag(name: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp),
    ) { Text(name, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

@Composable
private fun QualityRow(quality: String, hits: List<com.alyoushw.watchhub.ui.LiveHit>, colorOf: Map<String, Color>, accent: Color, onClick: () -> Unit) {
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
private fun ServerRow(quality: String, hit: com.alyoushw.watchhub.ui.LiveHit, color: Color, accent: Color, onClick: () -> Unit) {
    RowCard(onClick) {
        QualityBadge(quality, accent)
        Column(Modifier.weight(1f)) {
            ServerTag(hit.account.label, color)
        }
        Text("▶", color = accent)
    }
}
