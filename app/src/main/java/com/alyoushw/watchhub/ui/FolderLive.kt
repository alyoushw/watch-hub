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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
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

// Structure: section (رياضة/أخبار/أفلام…) ▸ brand (beIN Sport, ألوان, الثمانية…) ▸ channel number ▸
// quality ▸ server. Section and brand are canonical names matched by keyword from the provider
// category and channel name together; the number and resolution come from the name. English/French
// feeds are filtered out.

private data class Quality(val label: String, val hits: List<LiveHit>)
private data class ChannelNode(val key: String, val title: String, val number: Int?, val qualities: List<Quality>)
private data class Brand(val label: String, val channels: List<ChannelNode>)
private data class Section(val label: String, val brands: List<Brand>)

// A favourite can be a whole brand, a channel, or one quality — starred from wherever you are.
private fun brandFav(s: Section, b: Brand) = "B\u0001${s.label}\u0001${b.label}"
private fun channelFav(ch: ChannelNode) = "C\u0001${ch.key}"
private fun qualityFav(ch: ChannelNode, q: Quality) = "Q\u0001${ch.key}\u0001${q.label}"

private val QUALITY_ORDER = listOf("8k", "4k", "fhd", "hd", "sd", "hevc", "raw", "50fps", "60fps", "low")
private val SECTION_ORDER = listOf("رياضة", "أخبار", "أفلام", "مسلسلات", "أطفال", "دينية", "موسيقى", "وثائقي", "أخرى")

private fun normKey(s: String): String =
    s.lowercase().replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')

/** Superscript / modifier letters (ᴿᴬᵂ, ᴴᴰ, ʰᵉᵛᶜ…) some servers use for the quality → plain ASCII. */
private val SUPER = mapOf(
    'ᴬ' to 'A', 'ᴮ' to 'B', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᴳ' to 'G', 'ᴴ' to 'H',
    'ᴵ' to 'I', 'ᴶ' to 'J', 'ᴷ' to 'K', 'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N',
    'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R', 'ᵀ' to 'T', 'ᵁ' to 'U', 'ᵂ' to 'W',
    'ⱽ' to 'V',
    'ʰ' to 'h', 'ʲ' to 'j', 'ʳ' to 'r', 'ʷ' to 'w', 'ʸ' to 'y', 'ˡ' to 'l',
    'ˢ' to 's', 'ˣ' to 'x', 'ᵃ' to 'a', 'ᵇ' to 'b', 'ᶜ' to 'c', 'ᵈ' to 'd',
    'ᵉ' to 'e', 'ᶠ' to 'f', 'ᵍ' to 'g', 'ᶦ' to 'i', 'ᵏ' to 'k', 'ᵐ' to 'm',
    'ⁿ' to 'n', 'ᵒ' to 'o', 'ᵖ' to 'p', 'ᵗ' to 't', 'ᵘ' to 'u', 'ᵛ' to 'v',
    '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5',
    '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
)

internal fun deSuper(s: String): String = if (s.none { it in SUPER }) s else buildString { for (c in s) append(SUPER[c] ?: c) }

private fun natKey(s: String): String = Regex("\\d+").replace(s) { it.value.padStart(6, '0') }

/** Feeds the owner does not want listed: foreign languages, France (FR), Turkey (TK/TR), Extra. */
private fun isUnwanted(name: String): Boolean {
    val n = normKey(name)
    if (listOf("english", "anglais", "french", "francais", "franc", "espanol", "deutsch", "italiano", "portugu", "turkish", "turkey", "extra", "xtra").any { it in n }) return true
    return Regex("\\b(fr|tk|tr)\\b").containsMatchIn(n)
}

/** Drops a leading feed prefix like "UHD:", "F:", "NM:", "VIP:", "AR:" so it does not pollute the
 *  quality or the brand (the resolution lives at the end of the name). */
private fun stripFeedPrefix(name: String): String =
    name.replaceFirst(Regex("^\\s*[\\p{L}0-9]{1,6}\\s*:\\s*"), "")

/** The top section of a channel from its category and name. */
private fun sectionOf(text: String): String {
    val t = normKey(text)
    fun has(vararg k: String) = k.any { it in t }
    return when {
        has("news", "اخبار", "jazeera", "الجزير", "alarabiya", "العربيه", "الحدث", "sky news", "cnn", "bbc", "euronews") -> "أخبار"
        has("kids", "cartoon", "atfal", "اطفال", "spacetoon", "سبيستون", "baraem", "براعم", "jeem", "جيم", "majid", "ماجد", "disney", "nick") -> "أطفال"
        has("aflam", "افلam", "افلام", "movie", "cinema", "سينما", "film", "box office", "mbc2", "mbc max", "rotana cinema", "osn movies", "action") -> "أفلام"
        has("series", "drama", "دراما", "مسلسل", "mbc4", "mbc drama", "rotana drama", "osn series", "shahid", "شاهد") -> "مسلسلات"
        has("quran", "قران", "قرآن", "sunnah", "islam", "اسلام", "اقرا", "المجد", "مكه", "المدينه", "دينيه") -> "دينية"
        has("documentary", "وثائق", "nat geo", "discovery", "history", "animal planet") -> "وثائقي"
        has("music", "موسيق", "اغاني", "clip", "كليب", "mtv", "mazzika", "مزيكا") -> "موسيقى"
        has("sport", "سبورت", "رياض", "bein", "بين", "ssc", "alkass", "الكاس", "kass", "dazn", "koora", "كوره", "كوورة", "football", "nba", "ufc", "wwe", "thmany", "ثماني", "arryadia", "الرياضي", "abu dhabi", "dubai sport", "ad sport") -> "رياضة"
        else -> "أخرى"
    }
}

private fun qualityLabel(name: String): String {
    val tags = qualityTags(name)
    if (tags.isEmpty()) return "عادي"
    return tags.sortedBy { QUALITY_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }.joinToString(" ") { it.uppercase() }
}

private fun qualityRank(label: String): Int =
    QUALITY_ORDER.indexOf(label.lowercase().substringBefore(' ')).let { if (it < 0) 90 else it }

private val BRAND_DECORATORS = setOf("ar", "en", "eng", "عربي", "عربيه", "vip", "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "raw", "h265", "hdr", "tv", "plus", "backup", "new")

private fun canonicalBrand(categoryAndName: String): String {
    val n = normKey(categoryAndName)
    fun has(vararg k: String) = k.any { it in n }
    return when {
        has("bein", "بين") && has("sport", "سبورت", "رياض") -> "beIN Sport"
        has("bein", "بين") && Regex("\\bbe?in\\b\\s*\\d").containsMatchIn(n) -> "beIN Sport"
        has("bein", "بين") && has("news", "اخبار") -> "beIN News"
        has("bein", "بين") -> "beIN"
        has("الوان", "alwan") -> "ألوان"
        has("ثماني", "ثمانيه", "thmany", "thamany", "thamanya") -> "الثمانية"
        has("سولو", "solo") -> "سولو"
        has("ssc") -> "SSC"
        has("alkass", "الكاس", "kass") -> "الكأس"
        has("relax") -> "Relax"
        else -> cleanBrand(categoryAndName)
    }
}

private fun cleanBrand(text: String): String {
    val base = text.replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ").replace("|", " ")
    val tokens = base.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
    var sawLetter = false
    val kept = tokens.filter { t ->
        val keep = normKey(t) !in BRAND_DECORATORS && !(t.length == 1 && t[0].isLetter()) && !(t.all { it.isDigit() } && sawLetter)
        if (t.any { it.isLetter() }) sawLetter = true
        keep
    }
    return kept.joinToString(" ").trim().ifBlank { text.trim() }
}

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
    "الكأس" -> 50
    "beIN", "beIN News" -> 900
    else -> 500
}

private suspend fun <T> twice(block: suspend () -> List<T>): List<T>? {
    repeat(2) { attempt ->
        val r = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        if (!r.isNullOrEmpty()) return r
        if (attempt == 0) kotlinx.coroutines.delay(700)
    }
    return null
}

private class FolderCache(val key: String, val at: Long, val sections: List<Section>, val all: Map<String, ChannelNode>)
private var folderCache: FolderCache? = null

private class FolderLiveViewModel(
    private val api: XtreamApi,
    private val accounts: List<XtreamAccount>,
) : ViewModel() {
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sections by mutableStateOf<List<Section>>(emptyList())
        private set
    var allChannels by mutableStateOf<Map<String, ChannelNode>>(emptyMap())
        private set

    init {
        val key = accounts.filter { it.isComplete }.joinToString { it.id }
        val hit = folderCache
        if (hit != null && hit.key == key && System.currentTimeMillis() - hit.at < 10 * 60_000L) {
            sections = hit.sections; allChannels = hit.all; loading = false
        } else load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val usable = accounts.filter { it.isComplete }
                if (usable.isEmpty()) { sections = emptyList(); loading = false; return@launch }
                // Accounts side by side; within one account the two calls go one after the other,
                // since many IPTV servers refuse a second connection at the same moment.
                val loaded = coroutineScope {
                    usable.map { acc ->
                        async {
                            val cats = twice { api.categories(XtreamKind.LIVE, acc) }.orEmpty().associate { it.id to it.name }
                            val chans = twice { api.all(XtreamKind.LIVE, acc) }.orEmpty()
                            Triple(acc, cats, chans)
                        }
                    }.awaitAll()
                }
                val whole = loaded.all { (_, cats, chans) -> cats.isNotEmpty() && chans.isNotEmpty() }
                val built = withContext(Dispatchers.Default) { build(loaded) }
                sections = built.first
                allChannels = built.second
                if (whole) folderCache = FolderCache(usable.joinToString { it.id }, System.currentTimeMillis(), built.first, built.second)
                if (sections.isEmpty()) error = "ما وصلت قنوات من الحسابات، تأكد من الاشتراك والإنترنت"
            } catch (e: Exception) {
                error = e.message ?: "تعذّر تحميل القنوات"
            }
            loading = false
        }
    }

    private fun build(loaded: List<Triple<XtreamAccount, Map<String, String>, List<XtreamItem>>>): Pair<List<Section>, Map<String, ChannelNode>> {
        class QAcc(val label: String, val hits: MutableList<LiveHit> = mutableListOf())
        class CAcc(val title: String, val num: Int?, val qualities: LinkedHashMap<String, QAcc> = linkedMapOf())
        class BAcc(val label: String, val channels: LinkedHashMap<String, CAcc> = linkedMapOf())
        class SAcc(val label: String, val brands: LinkedHashMap<String, BAcc> = linkedMapOf())
        val sectionMap = linkedMapOf<String, SAcc>()
        for ((acc, cats, chans) in loaded) {
            for (ch in chans) {
                val plain = deSuper(ch.name) // superscript quality letters → ASCII (ᴿᴬᵂ → RAW)
                if (isUnwanted(plain)) continue
                val catName = ch.categoryId?.let { cats[it] }.orEmpty()
                val name = stripFeedPrefix(plain) // parse the real name, not the "UHD:"/"F:" prefix
                val combined = "$catName $name"
                val section = sectionOf(combined)
                val bLabel = canonicalBrand(combined)
                val num = numberOf(name)
                val qLabel = qualityLabel(name)
                val chTitle = if (num != null) "$bLabel $num" else cleanTitle(name)
                val chKey = num?.toString() ?: ("n:" + normKey(chTitle).filter { it.isLetterOrDigit() })
                val s = sectionMap.getOrPut(section) { SAcc(section) }
                val b = s.brands.getOrPut(bLabel) { BAcc(bLabel) }
                val c = b.channels.getOrPut(chKey) { CAcc(chTitle, num) }
                val q = c.qualities.getOrPut(qLabel) { QAcc(qLabel) }
                q.hits.add(LiveHit(ch, acc))
            }
        }
        val all = linkedMapOf<String, ChannelNode>()
        val sections = sectionMap.entries
            .sortedBy { SECTION_ORDER.indexOf(it.key).let { i -> if (i < 0) 50 else i } }
            .map { (_, s) ->
                val brands = s.brands.entries
                    .sortedWith(compareBy({ brandPriority(it.key) }, { it.value.label }))
                    .map { (_, b) ->
                        val channels = b.channels.entries
                            .sortedWith(compareBy({ it.value.num ?: Int.MAX_VALUE }, { natKey(it.value.title) }))
                            .map { (_, c) ->
                                val qualities = c.qualities.entries.sortedBy { qualityRank(it.value.label) }
                                    .map { (_, q) -> Quality(q.label, q.hits.sortedBy { natKey(it.channel.name) }) }
                                val node = ChannelNode("${s.label}\u0001${b.label}\u0001${c.title}", c.title, c.num, qualities)
                                all[node.key] = node
                                node
                            }
                        Brand(b.label, channels)
                    }
                Section(s.label, brands)
            }
        return sections to all
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
    data class SectionLevel(val section: Section) : Level
    data class BrandLevel(val section: Section, val brand: Brand) : Level
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

    if (vm.loading && vm.sections.isEmpty()) { CenteredLoading(); return }
    if (vm.error != null && vm.sections.isEmpty()) { MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::load); return }

    val accent = MaterialTheme.colorScheme.primary
    fun push(l: Level) { stack = stack + l }
    fun fav(k: String) = container.settings.toggleFolderFavorite(k)

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

    fun openQuality(ch: ChannelNode, q: Quality, go: (Level) -> Unit) {
        if (q.hits.size <= 1) play(q.hits.first(), q.hits) else go(Level.QualityLevel(ch, q))
    }

    Column(Modifier.fillMaxSize()) {
        val crumbs = buildList {
            add("المباشر")
            stack.drop(1).forEach {
                add(
                    when (it) {
                        Level.Favorites -> "المفضلة"
                        is Level.SectionLevel -> it.section.label
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
        fun isFolder(l: Level) = l is Level.Root || l is Level.Favorites || l is Level.SectionLevel
        val left = stack.last { isFolder(it) }
        val right = stack.lastOrNull { !isFolder(it) }
        // From the folder pane a folder opens inside it; a group or channel opens in the right pane.
        fun fromLeft(l: Level) { stack = stack.filter { isFolder(it) } + l }
        fun fromRight(l: Level) { stack = stack + l }

        fun LazyListScope.levelItems(level: Level, go: (Level) -> Unit) {
            when (level) {
                Level.Root -> {
                    if (favorites.isNotEmpty()) item { FolderRow("⭐ المفضلة", "${favorites.size} مفضّلة") { go(Level.Favorites) } }
                    items(vm.sections) { s -> FolderRow(s.label, "${s.brands.size} مجموعة") { go(Level.SectionLevel(s)) } }
                }
                Level.Favorites -> {
                    if (favorites.isEmpty()) item { Text("لا توجد مفضّلة بعد. اضغط مطولاً على أي مجموعة أو قناة أو جودة لإضافتها.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    // Walked in tree order, so favourites stay sorted the same way as the folders.
                    vm.sections.forEach { s ->
                        s.brands.forEach { b ->
                            val bk = brandFav(s, b)
                            if (bk in favorites) item(key = bk) { FolderRow(b.label, "${b.channels.size} قناة · ${s.label}", favorite = true, onLong = { fav(bk) }) { go(Level.BrandLevel(s, b)) } }
                            b.channels.forEach { ch ->
                                val ck = channelFav(ch)
                                if (ck in favorites) item(key = ck) { ChannelRow(ch, colorOf, favorite = true, onClick = { go(Level.ChannelLevel(ch)) }, onLong = { fav(ck) }) }
                                ch.qualities.forEach { q ->
                                    val qk = qualityFav(ch, q)
                                    if (qk in favorites) item(key = qk) { QualityRow(q, colorOf, accent, favorite = true, onLong = { fav(qk) }) { openQuality(ch, q, go) } }
                                }
                            }
                        }
                    }
                }
                is Level.SectionLevel -> items(level.section.brands) { b ->
                    val bk = brandFav(level.section, b)
                    FolderRow(b.label, "${b.channels.size} قناة", favorite = bk in favorites, onLong = { fav(bk) }) { go(Level.BrandLevel(level.section, b)) }
                }
                is Level.BrandLevel -> items(level.brand.channels) { ch ->
                    val ck = channelFav(ch)
                    ChannelRow(ch, colorOf, favorite = ck in favorites, onClick = { go(Level.ChannelLevel(ch)) }, onLong = { fav(ck) })
                }
                is Level.ChannelLevel -> items(level.channel.qualities) { q ->
                    val qk = qualityFav(level.channel, q)
                    QualityRow(q, colorOf, accent, favorite = qk in favorites, onLong = { fav(qk) }) { openQuality(level.channel, q, go) }
                }
                is Level.QualityLevel -> items(level.quality.hits) { hit ->
                    ServerRow(hit, colorOf[hit.account.id] ?: accent, accent) { play(hit, level.quality.hits) }
                }
            }
        }

        val folderWidth = (LocalConfiguration.current.screenWidthDp.dp * 0.45f).coerceIn(140.dp, 360.dp)
        // Right-to-left: the first child sits on the right, so the folders end up on the left.
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (right == null) {
                    item { Text("اختر مجموعة من اليسار", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    levelItems(right, ::fromRight)
                }
            }
            LazyColumn(
                Modifier.width(folderWidth).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                levelItems(left, ::fromLeft)
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp), content = content)
    }
}

@Composable
private fun FolderRow(title: String, subtitle: String, favorite: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    RowCard(onClick, onLong) {
        if (favorite) Text("⭐", fontSize = 13.sp)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text(ch.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("${ch.qualities.size} جودة", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ServerDots(hits, colorOf)
            }
        }
        Text("‹", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QualityRow(q: Quality, colorOf: Map<String, Color>, accent: Color, favorite: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val single = q.hits.size <= 1
    RowCard(onClick, onLong) {
        if (favorite) Text("⭐", fontSize = 13.sp)
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
            Text(deSuper(hit.channel.name), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("▶", color = accent)
    }
}
