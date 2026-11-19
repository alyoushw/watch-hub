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
import com.alyoushw.watchhub.player.qualityTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Structure: brand (beIN, 8, ألوان, سولو…) ▸ quality (4K, RAW, FHD…) ▸ channel number (1, 2, 3…) ▸
// server. The brand, number and quality are read from the channel NAME, so the provider's category
// names are ignored and "AR beIN Sports 1 4K" and "beIN 1 4K RAW" fold together correctly.

private data class FolderChannel(val key: String, val title: String, val hits: List<LiveHit>)
private data class QualityBlock(val label: String, val channels: List<FolderChannel>)
private data class Brand(val label: String, val qualities: List<QualityBlock>)

private val QUALITY_ORDER = listOf("8k", "4k", "fhd", "hd", "sd", "hevc", "raw", "50fps", "60fps", "low")
private val NAME_DECORATORS = setOf(
    "ar", "en", "eng", "عربي", "عربيه", "vip", "hd", "fhd", "uhd", "sd", "4k", "8k",
    "hevc", "raw", "h265", "50fps", "60fps", "fps", "tv", "plus", "backup", "new", "hd2",
)

private fun normKey(s: String): String =
    s.lowercase().replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')

/** The brand of a channel from its name: quality words, region letters and the channel number removed. */
private fun brandOf(name: String): String {
    val base = name.replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ").replace("|", " ")
    val tokens = base.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
    var sawLetter = false
    val kept = tokens.filter { t ->
        val k = normKey(t)
        val isDigits = t.all { it.isDigit() }
        val isSingleLetter = t.length == 1 && t[0].isLetter()
        val keep = when {
            k in NAME_DECORATORS -> false
            isSingleLetter -> false
            isDigits -> !sawLetter // a leading number ("8") is a brand; a later one is a channel number
            else -> true
        }
        if (t.any { it.isLetter() }) sawLetter = true
        keep
    }
    return kept.joinToString(" ").trim().ifBlank { name.trim() }
}

/** The channel number in a name ("beIN Sports 1 4K" → 1), ignoring quality numbers (4K, H265). */
private fun numberOf(name: String): Int? {
    val cleaned = name.replace(Regex("(?i)\\b(4k|uhd|8k|fhd|hd|sd|raw|hevc|h265|50fps|60fps)\\b"), " ")
    var seenLetter = false
    var last: Int? = null
    for (tok in cleaned.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }) {
        if (tok.all { it.isDigit() }) { if (seenLetter) last = tok.toIntOrNull() ?: last }
        else if (tok.any { it.isLetter() }) seenLetter = true
    }
    return last
}

/** A single label for a channel's quality: its tags in order, or "عادي" when it has none. */
private fun qualityLabel(name: String): String {
    val tags = qualityTags(name)
    if (tags.isEmpty()) return "عادي"
    return tags.sortedBy { QUALITY_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
        .joinToString(" ") { it.uppercase() }
}

private fun qualityRank(label: String): Int =
    QUALITY_ORDER.indexOf(label.lowercase().substringBefore(' ')).let { if (it < 0) 99 else it }

/** Brand order: beIN sports first, then 8, ألوان, سولو, SSC; beIN entertainment and the rest last. */
private fun brandPriority(brandKey: String): Int = when {
    (brandKey.contains("bein") || brandKey.contains("بين")) && (brandKey.contains("sport") || brandKey.contains("رياض")) -> 0
    brandKey.startsWith("8") -> 10
    brandKey.contains("الوان") || brandKey.contains("alwan") -> 20
    brandKey.contains("سولو") || brandKey.contains("solo") -> 30
    brandKey.contains("ssc") -> 40
    brandKey.contains("bein") || brandKey.contains("بين") -> 900 // beIN movies/series/entertainment
    else -> 500
}

private fun natKey(s: String): String = Regex("\\d+").replace(s) { it.value.padStart(6, '0') }

private class FolderLiveViewModel(
    private val api: XtreamApi,
    private val accounts: List<XtreamAccount>,
) : ViewModel() {
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var brands by mutableStateOf<List<Brand>>(emptyList())
        private set
    var allChannels by mutableStateOf<Map<String, FolderChannel>>(emptyMap())
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val usable = accounts.filter { it.isComplete }
                if (usable.isEmpty()) { brands = emptyList(); loading = false; return@launch }
                val loaded = coroutineScope {
                    usable.map { acc -> async { acc to runCatching { api.all(XtreamKind.LIVE, acc) }.getOrDefault(emptyList()) } }.awaitAll()
                }
                val built = withContext(Dispatchers.Default) { build(loaded) }
                brands = built.first
                allChannels = built.second
                if (brands.isEmpty()) error = "ما وصلت قنوات من الحسابات، تأكد من الاشتراك والإنترنت"
            } catch (e: Exception) {
                error = e.message ?: "تعذّر تحميل القنوات"
            }
            loading = false
        }
    }

    private fun build(loaded: List<Pair<XtreamAccount, List<XtreamItem>>>): Pair<List<Brand>, Map<String, FolderChannel>> {
        class ChAcc(val title: String, val num: Int?, val hits: MutableList<LiveHit> = mutableListOf())
        class QAcc(val label: String, val channels: LinkedHashMap<String, ChAcc> = linkedMapOf())
        class BAcc(val label: String, val qualities: LinkedHashMap<String, QAcc> = linkedMapOf())
        val brandMap = linkedMapOf<String, BAcc>()
        for ((acc, chans) in loaded) {
            for (ch in chans) {
                val name = ch.name
                val bLabel = brandOf(name)
                val bKey = normKey(bLabel).replace(" ", "")
                val qLabel = qualityLabel(name)
                val num = numberOf(name)
                val chKey = num?.toString() ?: normKey(name).filter { it.isLetterOrDigit() }
                val b = brandMap.getOrPut(bKey) { BAcc(bLabel) }
                val q = b.qualities.getOrPut(qLabel) { QAcc(qLabel) }
                val c = q.channels.getOrPut(chKey) { ChAcc(bLabel + (num?.let { " $it" } ?: ""), num) }
                c.hits.add(LiveHit(ch, acc))
            }
        }
        val all = linkedMapOf<String, FolderChannel>()
        val brands = brandMap.entries
            .sortedWith(compareBy({ brandPriority(it.key) }, { it.value.label }))
            .map { (bKey, b) ->
                val qualities = b.qualities.entries.sortedBy { qualityRank(it.key) }.map { (_, q) ->
                    val channels = q.channels.entries
                        .sortedWith(compareBy({ it.value.num ?: Int.MAX_VALUE }, { natKey(it.value.title) }))
                        .map { (chKey, c) ->
                            val fc = FolderChannel("$bKey\u0001${q.label}\u0001$chKey", c.title, c.hits.sortedBy { it.account.label })
                            all[fc.key] = fc
                            fc
                        }
                    QualityBlock(q.label, channels)
                }
                Brand(b.label, qualities)
            }
        return brands to all
    }
}

/** A colour per server so every quality shows which server it is at a glance. */
private val SERVER_COLORS = listOf(
    Color(0xFF4BB3D4), Color(0xFF57C98A), Color(0xFFB38BF0),
    Color(0xFFE0863C), Color(0xFFE5894D), Color(0xFF8AA4FF), Color(0xFFD98AC0),
)

private sealed interface Level {
    data object Root : Level
    data object Favorites : Level
    data class BrandLevel(val brand: Brand) : Level
    data class QualityLevel(val brand: Brand, val block: QualityBlock) : Level
    data class ChannelLevel(val channel: FolderChannel) : Level
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

    if (vm.loading && vm.brands.isEmpty()) { CenteredLoading(); return }
    if (vm.error != null && vm.brands.isEmpty()) { MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::load); return }

    val accent = MaterialTheme.colorScheme.primary
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

    // Tapping a channel: one server plays at once; several open the server picker.
    fun openChannel(ch: FolderChannel) {
        if (ch.hits.size <= 1) play(ch.hits.first(), ch.hits) else push(Level.ChannelLevel(ch))
    }

    Column(Modifier.fillMaxSize()) {
        val crumbs = buildList {
            add("المباشر")
            stack.drop(1).forEach {
                add(
                    when (it) {
                        Level.Favorites -> "المفضلة"
                        is Level.BrandLevel -> it.brand.label
                        is Level.QualityLevel -> "${it.brand.label} ${it.block.label}"
                        is Level.ChannelLevel -> it.channel.title
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
            Level.Root -> "اختر المجموعة"
            Level.Favorites -> "المفضلة"
            is Level.BrandLevel -> "اختر الجودة"
            is Level.QualityLevel -> "اختر القناة"
            is Level.ChannelLevel -> "اختر السيرفر"
        }
        Text(prompt, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)

        LazyColumn(
            Modifier.fillMaxSize().readableWidth(900.dp),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (cur) {
                Level.Root -> {
                    if (favorites.isNotEmpty()) item { FolderRow("⭐ المفضلة", "${favorites.size} قناة") { push(Level.Favorites) } }
                    items(vm.brands) { b ->
                        FolderRow(b.label, "${b.qualities.size} جودة") { push(Level.BrandLevel(b)) }
                    }
                }
                Level.Favorites -> {
                    val favChannels = vm.allChannels.values.filter { it.key in favorites }.sortedBy { natKey(it.title) }
                    if (favChannels.isEmpty()) item { Text("لا توجد قنوات مفضّلة بعد. اضغط مطولاً على أي قناة لإضافتها.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(favChannels) { ch ->
                        ChannelRow(ch, colorOf, favorite = true, onClick = { openChannel(ch) }, onLong = { container.settings.toggleFolderFavorite(ch.key) })
                    }
                }
                is Level.BrandLevel -> items(cur.brand.qualities) { qb ->
                    val chans = qb.channels.size
                    QualityFolderRow("${cur.brand.label} ${qb.label}", "$chans قناة", qb.channels.flatMap { it.hits }, colorOf, accent) {
                        push(Level.QualityLevel(cur.brand, qb))
                    }
                }
                is Level.QualityLevel -> items(cur.block.channels) { ch ->
                    ChannelRow(ch, colorOf, favorite = ch.key in favorites, onClick = { openChannel(ch) }, onLong = { container.settings.toggleFolderFavorite(ch.key) })
                }
                is Level.ChannelLevel -> items(cur.channel.hits) { hit ->
                    ServerRow(hit, colorOf[hit.account.id] ?: accent, accent) { play(hit, cur.channel.hits) }
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
private fun QualityFolderRow(title: String, subtitle: String, hits: List<LiveHit>, colorOf: Map<String, Color>, accent: Color, onClick: () -> Unit) {
    RowCard(onClick) {
        Box(Modifier.clip(RoundedCornerShape(7.dp)).background(accent).padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text(title.substringAfterLast(' '), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
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
private fun ChannelRow(ch: FolderChannel, colorOf: Map<String, Color>, favorite: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val servers = ch.hits.map { it.account.id }.distinct().size
    RowCard(onClick, onLong) {
        if (favorite) Text("⭐", fontSize = 13.sp)
        Column(Modifier.weight(1f)) {
            Text(ch.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (servers > 1) "$servers سيرفر" else "سيرفر واحد", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ServerDots(ch.hits, colorOf)
            }
        }
        Text(if (servers > 1) "‹" else "▶", color = if (servers > 1) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ServerTag(name: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(name, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun ServerRow(hit: LiveHit, color: Color, accent: Color, onClick: () -> Unit) {
    RowCard(onClick) {
        Column(Modifier.weight(1f)) { ServerTag(hit.account.label, color) }
        Text("▶", color = accent)
    }
}
