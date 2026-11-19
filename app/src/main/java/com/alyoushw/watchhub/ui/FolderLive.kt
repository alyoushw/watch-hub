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

// Structure: brand ▸ channel number ▸ quality ▸ server. The brand is one canonical name per family
// (every "BEIN SPORTS", "BEIN SPORT TOD", "beIN 1 (F)"… fold into one "beIN Sport"), read from the
// provider category and the channel name together. The number and the resolution come from the
// channel name; the leaf is each real feed, labelled by its server so all servers sit side by side.

private data class Quality(val label: String, val hits: List<LiveHit>)
private data class ChannelNode(val key: String, val title: String, val number: Int?, val qualities: List<Quality>)
private data class Brand(val label: String, val channels: List<ChannelNode>)

private val QUALITY_ORDER = listOf("8k", "4k", "fhd", "hd", "sd", "hevc", "raw", "50fps", "60fps", "low")

private fun normKey(s: String): String =
    s.lowercase().replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')

private fun natKey(s: String): String = Regex("\\d+").replace(s) { it.value.padStart(6, '0') }

/** The resolution of a channel ("beIN 1 (F) - 4K" → "4K"), ignoring the region feed. */
private fun qualityLabel(name: String): String {
    val tags = qualityTags(name)
    if (tags.isEmpty()) return "عادي"
    return tags.sortedBy { QUALITY_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }.joinToString(" ") { it.uppercase() }
}

private fun qualityRank(label: String): Int =
    QUALITY_ORDER.indexOf(label.lowercase().substringBefore(' ')).let { if (it < 0) 90 else it }

/** One canonical brand for a whole family, from the category name and the channel name together. */
private fun canonicalBrand(categoryAndName: String): String {
    val n = normKey(categoryAndName)
    fun has(vararg k: String) = k.any { it in n }
    return when {
        has("bein", "بين") && has("sport", "سبورت", "رياض") -> "beIN Sport"
        has("bein", "بين") && has("news", "اخبار") -> "beIN News"
        has("bein", "بين") && Regex("\\bbe?in\\b\\s*\\d").containsMatchIn(n) -> "beIN Sport" // "beIN 1", "beIN 2"…
        has("bein", "بين") -> "beIN"
        has("الوان", "alwan") -> "ألوان"
        has("ثماني", "ثمانيه", "thmany", "thamany", "thamanya") -> "الثمانية"
        has("سولو", "solo") -> "سولو"
        has("ssc") -> "SSC"
        has("relax") -> "Relax"
        else -> cleanBrand(categoryAndName)
    }
}

private val BRAND_DECORATORS = setOf("ar", "en", "eng", "عربي", "عربيه", "vip", "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "raw", "h265", "hdr", "tv", "plus", "backup", "new")

/** Fallback brand for an unknown family: the category/name minus decorators, region letters and numbers. */
private fun cleanBrand(text: String): String {
    val base = text.replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ").replace("|", " ")
    val tokens = base.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
    var sawLetter = false
    val kept = tokens.filter { t ->
        val keep = normKey(t) !in BRAND_DECORATORS && !(t.length == 1 && t[0].isLetter()) &&
            !(t.all { it.isDigit() } && sawLetter)
        if (t.any { it.isLetter() }) sawLetter = true
        keep
    }
    return kept.joinToString(" ").trim().ifBlank { text.trim() }
}

/** The channel number in a name ("beIN 1 (F) - 4K" → 1), ignoring the resolution number. */
private fun numberOf(name: String): Int? {
    val cleaned = name.replace(Regex("(?i)\\b(4k|8k|uhd|fhd|hd|sd|raw|hevc|h265|hdr|50fps|60fps)\\b"), " ")
    var seenLetter = false
    var last: Int? = null
    for (tok in cleaned.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }) {
        if (tok.all { it.isDigit() }) { if (seenLetter) last = tok.toIntOrNull() ?: last }
        else if (tok.any { it.isLetter() }) seenLetter = true
    }
    return last
}

private fun cleanTitle(name: String): String = name
    .replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ")
    .replace(Regex("(?i)\\b(4k|8k|uhd|fhd|hd|sd|raw|hevc|h265|hdr)\\b"), " ")
    .replace(Regex("\\s+"), " ").trim().ifBlank { name.trim() }

private fun brandPriority(label: String): Int = when (label) {
    "beIN Sport" -> 0
    "الثمانية" -> 10
    "ألوان" -> 20
    "سولو" -> 30
    "SSC" -> 40
    "beIN", "beIN News" -> 900
    else -> 500
}

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
    var allChannels by mutableStateOf<Map<String, ChannelNode>>(emptyMap())
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
                    usable.map { acc ->
                        async {
                            val cats = runCatching { api.categories(XtreamKind.LIVE, acc) }.getOrDefault(emptyList()).associate { it.id to it.name }
                            val chans = runCatching { api.all(XtreamKind.LIVE, acc) }.getOrDefault(emptyList())
                            Triple(acc, cats, chans)
                        }
                    }.awaitAll()
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

    private fun build(loaded: List<Triple<XtreamAccount, Map<String, String>, List<XtreamItem>>>): Pair<List<Brand>, Map<String, ChannelNode>> {
        class QAcc(val label: String, val hits: MutableList<LiveHit> = mutableListOf())
        class CAcc(val title: String, val num: Int?, val qualities: LinkedHashMap<String, QAcc> = linkedMapOf())
        class BAcc(val label: String, val channels: LinkedHashMap<String, CAcc> = linkedMapOf())
        val brandMap = linkedMapOf<String, BAcc>()
        for ((acc, cats, chans) in loaded) {
            for (ch in chans) {
                val catName = ch.categoryId?.let { cats[it] }.orEmpty()
                val bLabel = canonicalBrand("$catName ${ch.name}")
                val bKey = bLabel
                val num = numberOf(ch.name)
                val qLabel = qualityLabel(ch.name)
                val chTitle = if (num != null) "$bLabel $num" else cleanTitle(ch.name)
                val chKey = num?.toString() ?: ("n:" + normKey(chTitle).filter { it.isLetterOrDigit() })
                val b = brandMap.getOrPut(bKey) { BAcc(bLabel) }
                val c = b.channels.getOrPut(chKey) { CAcc(chTitle, num) }
                val q = c.qualities.getOrPut(qLabel) { QAcc(qLabel) }
                q.hits.add(LiveHit(ch, acc))
            }
        }
        val all = linkedMapOf<String, ChannelNode>()
        val brands = brandMap.entries
            .sortedWith(compareBy({ brandPriority(it.key) }, { it.value.label }))
            .map { (_, b) ->
                val channels = b.channels.entries
                    .sortedWith(compareBy({ it.value.num ?: Int.MAX_VALUE }, { natKey(it.value.title) }))
                    .map { (chKey, c) ->
                        val qualities = c.qualities.entries.sortedBy { qualityRank(it.value.label) }
                            .map { (_, q) -> Quality(q.label, q.hits.sortedBy { natKey(it.channel.name) }) }
                        val node = ChannelNode("${b.label}\u0001$chKey", c.title, c.num, qualities)
                        all[node.key] = node
                        node
                    }
                Brand(b.label, channels)
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
    data class ChannelLevel(val channel: ChannelNode) : Level
    data class QualityLevel(val channel: ChannelNode, val quality: Quality) : Level
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

    fun openQuality(ch: ChannelNode, q: Quality) {
        if (q.hits.size <= 1) play(q.hits.first(), q.hits) else push(Level.QualityLevel(ch, q))
    }

    Column(Modifier.fillMaxSize()) {
        val crumbs = buildList {
            add("المباشر")
            stack.drop(1).forEach {
                add(
                    when (it) {
                        Level.Favorites -> "المفضلة"
                        is Level.BrandLevel -> it.brand.label
                        is Level.ChannelLevel -> it.channel.title
                        is Level.QualityLevel -> it.quality.label
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
            is Level.BrandLevel -> "اختر القناة"
            is Level.ChannelLevel -> "اختر الجودة"
            is Level.QualityLevel -> "اختر السيرفر"
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
                    items(vm.brands) { b -> FolderRow(b.label, "${b.channels.size} قناة") { push(Level.BrandLevel(b)) } }
                }
                Level.Favorites -> {
                    val favChannels = vm.allChannels.values.filter { it.key in favorites }.sortedBy { natKey(it.title) }
                    if (favChannels.isEmpty()) item { Text("لا توجد قنوات مفضّلة بعد. اضغط مطولاً على أي قناة لإضافتها.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(favChannels) { ch -> ChannelRow(ch, colorOf, favorite = true, onClick = { push(Level.ChannelLevel(ch)) }, onLong = { container.settings.toggleFolderFavorite(ch.key) }) }
                }
                is Level.BrandLevel -> items(cur.brand.channels) { ch ->
                    ChannelRow(ch, colorOf, favorite = ch.key in favorites, onClick = { push(Level.ChannelLevel(ch)) }, onLong = { container.settings.toggleFolderFavorite(ch.key) })
                }
                is Level.ChannelLevel -> items(cur.channel.qualities) { q ->
                    QualityRow(q, colorOf, accent) { openQuality(cur.channel, q) }
                }
                is Level.QualityLevel -> items(cur.quality.hits) { hit ->
                    ServerRow(hit, colorOf[hit.account.id] ?: accent, accent) { play(hit, cur.quality.hits) }
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
private fun ChannelRow(ch: ChannelNode, colorOf: Map<String, Color>, favorite: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val hits = ch.qualities.flatMap { it.hits }
    RowCard(onClick, onLong) {
        if (favorite) Text("⭐", fontSize = 13.sp)
        Column(Modifier.weight(1f)) {
            Text(ch.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("${ch.qualities.size} جودة", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ServerDots(hits, colorOf)
            }
        }
        Text("‹", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QualityRow(q: Quality, colorOf: Map<String, Color>, accent: Color, onClick: () -> Unit) {
    val single = q.hits.size <= 1
    RowCard(onClick) {
        Box(Modifier.clip(RoundedCornerShape(7.dp)).background(accent).padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text(q.label, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f)) {
            Text("جودة ${q.label}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (single) "سيرفر واحد" else "${q.hits.size} تغذية", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ServerDots(q.hits, colorOf)
            }
        }
        Text(if (single) "▶" else "‹", color = if (single) accent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ServerRow(hit: LiveHit, color: Color, accent: Color, onClick: () -> Unit) {
    RowCard(onClick) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(hit.account.label, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(hit.channel.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("▶", color = accent)
    }
}
