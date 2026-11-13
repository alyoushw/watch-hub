package com.alyoushw.watchhub.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.data.XtreamAccount
import com.alyoushw.watchhub.data.XtreamApi
import com.alyoushw.watchhub.data.XtreamItem
import com.alyoushw.watchhub.data.XtreamKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One thing the player can play: its links (tried in order) and its history entry. */
data class QueueItem(
    val urls: List<String>,
    val entry: WatchEntry,
    /** For channels: the number to show ("12"), when known. */
    val number: Int? = null,
    /** For channels: the item itself (used to find the same channel in other accounts). */
    val channel: XtreamItem? = null,
    val subtitles: List<com.alyoushw.watchhub.data.SourceSubtitle> = emptyList(),
    val mimeType: String? = null,
    /** Ephemeral provider session headers; never persisted in viewing history. */
    val httpHeaders: Map<String, String> = emptyMap(),
)

/**
 * What the player goes through: the channels of the list it was opened from (left/right switch
 * channel), or a series' episodes (for "الحلقة الجاية"). Handed over in memory: lists can hold
 * thousands of channels, too many for an Intent.
 */
object PlayerQueue {
    var items: List<QueueItem> = emptyList()
    var index: Int = 0
}

/** Another source for the same channel: same name and quality, from any account. */
data class ChannelSource(val label: String, val account: String, val item: QueueItem)

/** What the player screen shows and reacts to; the activity changes it as playback moves. */
class PlayerSession {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var live by mutableStateOf(false)
    var number by mutableStateOf<Int?>(null)
    var hasPrevious by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    /** "الحلقة الجاية" card: the next episode's name, and seconds left before it starts by itself. */
    var upNext by mutableStateOf<String?>(null)
    var upNextSeconds by mutableIntStateOf(0)
    /** Set while left/right go through the same channel's sources (not the list it came from). */
    var filter by mutableStateOf<String?>(null)
    /** "تخطي المقدمة" is shown while this is set (the label on the button). */
    var skipIntroLabel by mutableStateOf<String?>(null)
    /** The video keeps stopping to load: the "النت بطيء" card shows (with this explanation). */
    var slowNet by mutableStateOf<String?>(null)
    /** Loading ahead before going on: (ms ready, ms wanted). */
    var preloading by mutableStateOf<Pair<Long, Long>?>(null)
    /** "تلقائي دايماً": load ahead by itself whenever it starts to stall. */
    var autoPreload by mutableStateOf(false)
    var preparingSource by mutableStateOf(false)
    var sourceProgress by mutableStateOf<Float?>(null)
    var sourceQualities by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var sourceQuality by mutableStateOf<String?>(null)
    var sourceMessage by mutableStateOf<String?>(null)
}

/**
 * Where a series' opening and closing credits are, learned from what the user does:
 * pressing "تخطي المقدمة" (and adjusting after) teaches the intro; pressing "الحلقة الجاية"
 * early teaches how long the end credits are. Kept per series, on the device.
 */
class SeriesMarks(context: Context) {
    private val prefs = context.getSharedPreferences("series_marks", Context.MODE_PRIVATE)

    fun intro(series: String): LongRange? = prefs.getString("intro:$series", null)?.split(',')
        ?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 && it[1] > it[0] }?.let { it[0]..it[1] }

    fun setIntro(series: String, start: Long, end: Long) {
        if (end > start) prefs.edit().putString("intro:$series", "$start,$end").apply()
    }

    /** How long before the end the credits start, in ms (learned), or null. */
    fun credits(series: String): Long? = prefs.getLong("credits:$series", -1).takeIf { it > 0 }

    fun setCredits(series: String, remainingMs: Long) {
        if (remainingMs in 10_000..240_000) prefs.edit().putLong("credits:$series", remainingMs).apply()
    }
}

/**
 * Each account's live channels grouped by [channelKey], built once (off the main thread) and kept,
 * so "نفس القناة في حسابات ثانية" answers at once after the first time.
 */
object ChannelIndex {
    private val cache = mutableMapOf<String, Map<String, List<XtreamItem>>>()
    private val lock = Mutex()

    suspend fun of(api: XtreamApi, account: XtreamAccount): Map<String, List<XtreamItem>> {
        val k = "${account.host}|${account.username}"
        lock.withLock { cache[k]?.let { return it } }
        val list = api.all(XtreamKind.LIVE, account)
        val map = withContext(Dispatchers.Default) { list.groupBy { channelKey(it.name) } }
        lock.withLock { cache[k] = map }
        return map
    }
}

/** Quality words in channel names ("MBC 1 4K", "MBC1 FHD", "beIN 1 RAW"). */
private val QualityWords = listOf("4k", "uhd", "8k", "fhd", "hd", "sd", "raw", "hevc", "h265", "50fps", "60fps", "low")

/** The quality tags of a channel name, e.g. {"4k"} or {"raw","fhd"}. */
fun qualityTags(name: String): Set<String> {
    val words = name.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).toSet()
    val tags = QualityWords.filter { it in words }.toMutableSet()
    if ("uhd" in tags) { tags.remove("uhd"); tags.add("4k") }
    if ("h265" in tags) { tags.remove("h265"); tags.add("hevc") }
    return tags
}

/** The channel itself without prefixes, quality words or symbols: "AR| MBC 1 4K ᴿᴬᵂ" → "mbc1". */
fun channelKey(name: String): String {
    var t = name.lowercase()
        .replace(Regex("^\\s*(\\[[^\\]]*\\]|\\|[^|]{1,6}\\||[a-z]{2,3}\\s*[|:]\\s*|[a-z]{2,3}\\s+-\\s+)"), "")
        .replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ")
    val words = t.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() && it !in QualityWords }
    t = words.joinToString("")
    return t
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
}
