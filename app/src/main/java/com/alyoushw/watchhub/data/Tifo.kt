package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One player on the pitch (lineup): [x],[y] are the feed's own pitch coordinates (0..1, own goal at y≈0.1). */
data class TPlayer(val id: Long, val name: String, val number: String, val role: String, val x: Float, val y: Float, val captain: Boolean, val rating: Double?, val country: String, val age: Int?)

data class TLineup(val formation: String, val coach: String?, val starters: List<TPlayer>, val subs: List<TPlayer>, val unavailable: List<String>)

data class TEvent(val minute: String, val type: String, val side: String, val player: String, val assist: String?, val out: String?, val how: String?, val score: String?)

data class TStat(val label: String, val home: String, val away: String, val homeShare: Float)

data class TChannel(val channel: String, val commentator: String)

/** Everything Tifo knows about one match (any part can be missing: the feed's coverage differs per competition). */
data class TifoMatch(
    val id: Long, val roundAr: String?, val venue: String?, val attendance: Int?, val referee: String?, val status: String,
    val probs: Triple<Int, Int, Int>?, val expectedGoals: Pair<Double, Double>?,
    val home: TLineup?, val away: TLineup?, val events: List<TEvent>, val stats: List<TStat>,
    val h2hSummary: String?, val h2h: List<String>,
)

/**
 * Tifo's open football data (tifo.elyoxe.com/v1, no key): Arabic names, line-ups with pitch positions, events, statistics,
 * head-to-head, referee, a win-probability model, and — from Kooora's TV table — the channel and commentator of a match.
 * Terms: free for apps if a visible link to tifo.elyoxe.com is shown near the numbers; no more than one request per 10 s per file.
 */
object TifoApi {
    const val SITE = "tifo.elyoxe.com"
    private const val BASE = "https://tifo.elyoxe.com/v1/"
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private class Hit(val at: Long, val json: JSONObject)
    private val cache = ConcurrentHashMap<String, Hit>()
    private val uae = ZoneId.of("Asia/Dubai")
    /** Venue names read from the day files (a match not started yet has no venue in its own file). */
    val venues = ConcurrentHashMap<Long, String>()

    private suspend fun get(path: String, ttl: Long): JSONObject? {
        val ttlSafe = maxOf(ttl, 15_000L) // never faster than the feed's own limit
        cache[path]?.let { if (System.currentTimeMillis() - it.at < ttlSafe) return it.json }
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(BASE + path).build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.string()?.let { JSONObject(it) }
                }
            }.getOrNull()
        }?.also { cache[path] = Hit(System.currentTimeMillis(), it) } ?: cache[path]?.json
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("\\b(fc|cf|sc|fk)\\b"), "").replace(Regex("[^a-z0-9\\u0621-\\u064a]"), "")

    /** The Tifo match id of an ESPN match: same kick-off (±25 min) and the same teams (English or Arabic names). */
    suspend fun find(m: LiveMatch): Long? {
        val day = Instant.ofEpochMilli(m.startMs).atZone(uae).toLocalDate()
        val rows = (listOf(day, day.minusDays(1)).mapNotNull { d -> get("day/" + d.format(DateTimeFormatter.BASIC_ISO_DATE) + ".json", 60_000L)?.optJSONArray("matches") })
        val he = norm(m.homeEn); val ae = norm(m.awayEn)
        for (arr in rows) for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val ko = runCatching { Instant.parse(x.optString("kickoff_utc")).toEpochMilli() }.getOrDefault(0L)
            if (kotlin.math.abs(ko - m.startMs) > 25 * 60_000L) continue
            val h = x.optJSONObject("home"); val a = x.optJSONObject("away")
            val sameH = norm(h?.optString("name").orEmpty()) == he || h?.optString("name_ar").orEmpty() == m.home
            val sameA = norm(a?.optString("name").orEmpty()) == ae || a?.optString("name_ar").orEmpty() == m.away
            if (sameH && sameA) {
                val v = x.optJSONObject("venue"); val vn = v?.let { o -> listOf("name_ar", "name_en").firstNotNullOfOrNull { k -> if (o.isNull(k)) null else o.optString(k).trim().ifBlank { null } } }
                if (vn != null) venues[x.optLong("id")] = listOfNotNull(vn, v?.let { o -> if (o.isNull("city")) null else o.optString("city").substringBefore(",").trim().ifBlank { null } }).joinToString("، ")
                return x.optLong("id")
            }
        }
        return null
    }

    private val STAT_AR = mapOf(
        "BallPossesion" to "الاستحواذ %", "expected_goals" to "الأهداف المتوقعة (xG)", "total_shots" to "إجمالي التسديدات", "ShotsOnTarget" to "على المرمى",
        "touches_opp_box" to "لمسات في منطقة الخصم", "big_chance" to "فرص خطيرة", "big_chance_missed_title" to "فرص خطيرة ضائعة",
        "accurate_passes" to "تمريرات دقيقة", "fouls" to "الأخطاء", "corners" to "الركنيات", "yellow_cards" to "البطاقات الصفراء",
        "red_cards" to "البطاقات الحمراء", "offsides" to "التسلل", "keeper_saves" to "تصديات الحارس", "passes" to "التمريرات",
        "shots_blocked" to "تسديدات محجوبة", "ShotsOffTarget" to "خارج المرمى",
    )
    private val EVENT_AR = mapOf("goal" to "هدف", "penalty_goal" to "هدف من ضربة جزاء", "own_goal" to "هدف عكسي", "yellow" to "بطاقة صفراء", "red" to "بطاقة حمراء", "sub" to "تبديل", "pen_miss" to "ضربة جزاء ضائعة")

    private fun players(a: JSONArray?): List<TPlayer> = buildList {
        if (a == null) return@buildList
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            val xy = p.optJSONObject("xy")
            add(TPlayer(p.optLong("id"), p.optString("name"), p.optString("number"), p.optString("role"),
                xy?.optDouble("x", -1.0)?.toFloat() ?: -1f, xy?.optDouble("y", -1.0)?.toFloat() ?: -1f,
                p.optBoolean("captain"), if (p.isNull("rating")) null else p.optDouble("rating"), p.optString("country"), if (p.isNull("age")) null else p.optInt("age")))
        }
    }

    private fun lineup(o: JSONObject?): TLineup? {
        if (o == null) return null
        val st = players(o.optJSONArray("starters"))
        if (st.isEmpty()) return null
        val un = o.optJSONArray("unavailable")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.ifBlank { null } } }.orEmpty()
        return TLineup(o.optString("formation"), o.optJSONObject("coach")?.let { c -> if (c.isNull("name")) null else c.optString("name").ifBlank { null } }, st, players(o.optJSONArray("subs")), un)
    }

    /** The full match file; a live match is read every 30 s, a finished one every 10 minutes. */
    suspend fun match(id: Long, live: Boolean): TifoMatch? {
        val j = get("match/$id.json", if (live) 30_000L else 10 * 60_000L) ?: return null
        val events = ArrayList<TEvent>()
        j.optJSONArray("events")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val type = e.optString("type"); val kind = EVENT_AR[type] ?: continue
                val sc = e.optJSONObject("score")?.let { "${it.optInt("home")}-${it.optInt("away")}" }
                events += TEvent(e.optInt("minute").toString() + (e.optInt("added", 0).takeIf { it > 0 }?.let { "+$it" } ?: "") + "'", kind, e.optString("team").takeIf { it == "home" || it == "away" } ?: "",
                    e.optJSONObject("player")?.optString("name").orEmpty(), e.optJSONObject("assist")?.optString("name"), e.optJSONObject("player_out")?.optString("name"),
                    e.optJSONObject("how")?.optString("ar")?.ifBlank { null }, sc)
            }
        }
        val stats = ArrayList<TStat>()
        j.optJSONArray("stats")?.let { groups ->
            for (g in 0 until groups.length()) {
                val items = groups.optJSONObject(g)?.optJSONArray("items") ?: continue
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val h = it.opt("home")?.toString().orEmpty(); val a = it.opt("away")?.toString().orEmpty()
                    val hn = h.substringBefore(' ').toFloatOrNull() ?: 0f; val an = a.substringBefore(' ').toFloatOrNull() ?: 0f
                    stats += TStat(STAT_AR[it.optString("key")] ?: it.optString("title"), h, a, if (hn + an <= 0f) 0.5f else hn / (hn + an))
                }
            }
        }
        val pr = j.optJSONObject("probs")
        val probs = pr?.takeIf { it.has("home") }?.let { Triple((it.optDouble("home") * 100).toInt(), (it.optDouble("draw") * 100).toInt(), (it.optDouble("away") * 100).toInt()) }
        val xg = pr?.optJSONArray("expected_goals")?.let { if (it.length() >= 2) it.optDouble(0) to it.optDouble(1) else null }
        val lu = j.optJSONObject("lineups")
        val h2hList = ArrayList<String>()
        j.optJSONObject("h2h")?.optJSONArray("matches")?.let { a ->
            for (i in 0 until a.length()) {
                val g = a.optJSONObject(i) ?: continue
                val sc = g.optJSONObject("score") ?: continue
                h2hList += g.optString("kickoff_utc").take(10) + " · " + g.optJSONObject("home")?.optString("name_ar").orEmpty() + " " + sc.optInt("home") + " - " + sc.optInt("away") + " " + g.optJSONObject("away")?.optString("name_ar").orEmpty()
            }
        }
        val hs = j.optJSONObject("h2h")?.optJSONObject("summary")?.let { "فوز المضيف ${it.optInt("home_wins")} · تعادل ${it.optInt("draws")} · فوز الضيف ${it.optInt("away_wins")}" }
        fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).trim().ifBlank { null }
        val ven = j.optJSONObject("venue")
        return TifoMatch(
            id, j.optString("round_ar").ifBlank { null }, ven?.str("name_ar") ?: ven?.str("name_en"),
            if (j.isNull("attendance")) null else j.optInt("attendance").takeIf { it > 0 }, j.optJSONObject("referee")?.str("name"), j.optString("status"),
            probs, xg, lineup(lu?.optJSONObject("home")), lineup(lu?.optJSONObject("away")), events, stats, hs, h2hList,
        )
    }

    private class Chan(val at: Long, val map: Map<Long, List<TChannel>>)
    private var chanCache: Chan? = null

    /** The channel(s) and commentator(s) of a match, from Tifo's TV table (empty until the channels announce them). */
    suspend fun channels(id: Long): List<TChannel> {
        chanCache?.let { if (System.currentTimeMillis() - it.at < 10 * 60_000L) return it.map[id].orEmpty() }
        val j = get("tv/commentators.json", 10 * 60_000L) ?: return chanCache?.map?.get(id).orEmpty()
        val map = HashMap<Long, MutableList<TChannel>>()
        val com = j.optJSONObject("commentators")
        com?.keys()?.forEach { k ->
            val c = com.optJSONObject(k) ?: return@forEach
            val name = c.optString("name")
            for (key in listOf("recent", "next")) {
                val a = c.optJSONArray(key) ?: continue
                for (i in 0 until a.length()) {
                    val e = a.optJSONArray(i) ?: continue
                    map.getOrPut(e.optLong(0)) { ArrayList() }.add(TChannel(e.optString(1), name))
                }
            }
        }
        chanCache = Chan(System.currentTimeMillis(), map)
        return map[id].orEmpty()
    }

    /** «beIN SPORTS 1 · عبدالله الغامدي» for the match list; null while Tifo has nothing for it. */
    suspend fun line(m: LiveMatch): String? {
        val id = find(m) ?: return null
        val c = channels(id).distinct().firstOrNull() ?: return null
        return c.channel + (if (c.commentator.isNotBlank()) " · " + c.commentator else "")
    }
}
