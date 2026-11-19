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

// Structure: brand (beIN, 8, ألوان, سولو…) ▸ channel number (beIN 1, 2, 3…) ▸ version/quality
// (4K, RAW, NM, SS…) ▸ server. Brand, number and version are read from the channel NAME, so the
// provider's category names are ignored and every "beIN Sports 1" variant folds under one channel.

private data class Variant(val label: String, val hits: List<LiveHit>)
private data class ChannelNode(val key: String, val title: String, val number: Int?, val variants: List<Variant>)
private data class Brand(val label: String, val channels: List<ChannelNode>)

private val QUALITY_ORDER = listOf("8k", "4k", "fhd", "hd", "sd", "hevc", "raw", "50fps", "60fps", "low")
private val PREFIXES = setOf("ar", "en", "eng", "عربي", "عربيه", "عربى")
private val NAME_DECORATORS = setOf(
    "ar", "en", "eng", "عربي", "عربيه", "vip", "hd", "fhd", "uhd", "sd", "4k", "8k",
    "hevc", "raw", "h265", "50fps", "60fps", "fps", "tv", "plus", "backup", "new",
)

private fun normKey(s: String): String =
    s.lowercase().replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')

private fun natKey(s: String): String = Regex("\\d+").replace(s) { it.value.padStart(6, '0') }

private fun qualityLabel(name: String): String {
    val tags = qualityTags(name)
    if (tags.isEmpty()) return "عادي"
    return tags.sortedBy { QUALITY_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }.joinToString(" ") { it.uppercase() }
}

private fun variantRank(label: String): Int =
    QUALITY_ORDER.indexOf(label.lowercase().substringBefore(' ')).let { if (it < 0) 90 else it }

/** Splits a channel name into (brand, channel number, version/quality). */
private fun parseChannel(name: String): Triple<String, Int?, String> {
    val cleaned = name.replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ")
    val tokens = cleaned.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
    var start = 0
    while (start < tokens.size && (normKey(tokens[start]) in PREFIXES || (tokens[start].length == 1 && tokens[start][0].isLetter()))) start++
    val rest = tokens.drop(start)
    var numIdx = -1
    var sawLetter = false
    for ((idx, t) in rest.withIndex()) {
        if (t.all { it.isDigit() }) { if (sawLetter) { numIdx = idx; break } }
        else if (t.any { it.isLetter() }) sawLetter = true
    }
    return if (numIdx >= 0) {
        val brand = rest.take(numIdx).filter { normKey(it) !in NAME_DECORATORS && it.length > 1 }.joinToString(" ").trim()
        val num = rest[numIdx].toIntOrNull()
        val variant = rest.drop(numIdx + 1)
            .filter { normKey(it) !in PREFIXES && it.lowercase() != "tv" && it.lowercase() != "backup" }
            .joinToString(" ").uppercase().trim().ifBlank { "عادي" }
        Triple(brand.ifBlank { name.trim() }, num, variant)
    } else {
        val brand = rest.filter { normKey(it) !in NAME_DECORATORS && it.length > 1 && !it.all { c -> c.isDigit() } }.joinToString(" ").trim()
        Triple(brand.ifBlank { name.trim() }, null, qualityLabel(name))
    }
}

/** Brand order: beIN sports first, then 8, ألوان, سولو, SSC; beIN entertainment and the rest last. */
private fun brandPriority(brandKey: String): Int = when {
    (brandKey.contains("bein") || brandKey.contains("بين")) && (brandKey.contains("sport") || brandKey.contains("رياض")) -> 0
    brandKey.startsWith("8") -> 10
    brandKey.contains("الوان") || brandKey.contains("alwan") -> 20
    brandKey.contains("سولو") || brandKey.contains("solo") -> 30
    brandKey.contains("ssc") -> 40
    brandKey.contains("bein") || brandKey.contains("بين") -> 900
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

    private fun build(loaded: List<Pair<XtreamAccount, List<XtreamItem>>>): Pair<List<Brand>, Map<String, ChannelNode>> {
        class VAcc(val label: String, val hits: MutableList<LiveHit> = mutableListOf())
        class CAcc(val title: String, val num: Int?, val variants: LinkedHashMap<String, VAcc> = linkedMapOf())
        class BAcc(val label: String, val channels: LinkedHashMap<String, CAcc> = linkedMapOf())
        val brandMap = linkedMapOf<String, BAcc>()
        for ((acc, chans) in loaded) {
            for (ch in chans) {
                val (bLabel, num, variant) = parseChannel(ch.name)
                val bKey = normKey(bLabel).replace(" ", "")
                val chKey = num?.toString() ?: ("b" + bKey)
                val vKey = normKey(variant)
                val b = brandMap.getOrPut(bKey) { BAcc(bLabel) }
                val c = b.channels.getOrPut(chKey) { CAcc(bLabel + (num?.let { " $it" } ?: ""), num) }
                val v = c.variants.getOrPut(vKey) { VAcc(variant) }
                v.hits.add(LiveHit(ch, acc))
            }
        }
        val all = linkedMapOf<String, ChannelNode>()
        val brands = brandMap.entries
            .sortedWith(compareBy({ brandPriority(it.key) }, { it.value.label }))
            .map { (bKey, b) ->
                val channels = b.channels.entries
                    .sortedWith(compareBy({ it.value.num ?: Int.MAX_VALUE }, { natKey(it.value.title) }))
                    .map { (chKey, c) ->
                        val variants = c.variants.entries.sortedBy { variantRank(it.value.label) }
                            .map { (_, v) -> Variant(v.label, v.hits.sortedBy { it.account.label }) }
                        val node = ChannelNode("$bKey\u0001$chKey", c.title, c.num, variants)
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
    data class VariantLevel(val channel: ChannelNode, val variant: Variant) : Level
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

    fun openVariant(ch: ChannelNode, v: Variant) {
        if (v.hits.size <= 1) play(v.hits.first(), v.hits) else push(Level.VariantLevel(ch, v))
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
                        is Level.VariantLevel -> it.variant.label
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
            is Level.VariantLevel -> "اختر السيرفر"
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
                is Level.ChannelLevel -> items(cur.channel.variants) { v ->
                    VariantRow(v, colorOf, accent) { openVariant(cur.channel, v) }
                }
                is Level.VariantLevel -> items(cur.variant.hits) { hit ->
                    ServerRow(hit, colorOf[hit.account.id] ?: accent, accent) { play(hit, cur.variant.hits) }
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
    val hits = ch.variants.flatMap { it.hits }
    RowCard(onClick, onLong) {
        if (favorite) Text("⭐", fontSize = 13.sp)
        Column(Modifier.weight(1f)) {
            Text(ch.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("${ch.variants.size} جودة", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ServerDots(hits, colorOf)
            }
        }
        Text("‹", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VariantRow(v: Variant, colorOf: Map<String, Color>, accent: Color, onClick: () -> Unit) {
    val single = v.hits.size <= 1
    RowCard(onClick) {
        Box(Modifier.clip(RoundedCornerShape(7.dp)).background(accent).padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text(v.label, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f)) {
            Text("جودة ${v.label}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (single) "سيرفر واحد" else "${v.hits.map { it.account.id }.distinct().size} سيرفر", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ServerDots(v.hits, colorOf)
            }
        }
        Text(if (single) "▶" else "‹", color = if (single) accent else MaterialTheme.colorScheme.onSurfaceVariant)
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
