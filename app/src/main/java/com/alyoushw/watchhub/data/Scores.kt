package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One football match. [state] is "in" (playing), "pre" (not started) or "post" (finished). */
data class LiveMatch(
    val id: String,
    val league: String,
    val leagueSlug: String,
    val homeId: String,
    val awayId: String,
    /** Arabic when known, else the feed's name. */
    val home: String,
    val away: String,
    val homeLogo: String?,
    val awayLogo: String?,
    val homeScore: String,
    val awayScore: String,
    val state: String,
    val clock: String,
    val startMs: Long,
    /** The channels the feed says carry it, when it says (often empty or not the ones in this region). */
    val broadcasts: List<String>,
)

/** League → name shown, and the channel names usually carrying it here (only a starting point). */
data class LeagueInfo(val slug: String, val name: String, val keywords: List<String>)

data class TeamInfo(val id: String, val name: String, val logo: String?, val colorHex: String?, val leagueSlug: String)

data class StandingRow(
    val pos: Int, val teamId: String, val team: String, val logo: String?,
    val played: Int, val won: Int, val drawn: Int, val lost: Int, val diff: Int, val points: Int,
)

/** Matches, tables and teams from ESPN's public football feed (unofficial, no key). */
object ScoresApi {
    val leagues = listOf(
        LeagueInfo("ksa.1", "الدوري السعودي", listOf("ssc", "ثمانية")),
        LeagueInfo("uefa.champions", "دوري أبطال أوروبا", listOf("bein", "ثمانية")),
        LeagueInfo("eng.1", "الدوري الإنجليزي", listOf("bein")),
        LeagueInfo("esp.1", "الدوري الإسباني", listOf("bein")),
        LeagueInfo("ita.1", "الدوري الإيطالي", listOf("bein")),
        LeagueInfo("ger.1", "الدوري الألماني", listOf("bein")),
        LeagueInfo("fra.1", "الدوري الفرنسي", listOf("bein")),
        LeagueInfo("uefa.europa", "الدوري الأوروبي", listOf("bein")),
        LeagueInfo("afc.champions", "دوري أبطال آسيا", listOf("ssc", "bein")),
    )

    fun league(slug: String) = leagues.firstOrNull { it.slug == slug }

    /** League crests the feed gave us (filled while loading matches). */
    val leagueLogos = ConcurrentHashMap<String, String>()

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val day = DateTimeFormatter.ofPattern("yyyyMMdd")

    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()?.let { JSONObject(it) }
        }
    }

    /** Which leagues' boards are worth asking: the chosen leagues, the leagues of the chosen clubs, and the cups when clubs are chosen. */
    fun relevant(favLeagues: Set<String>, favTeams: Set<String>): List<LeagueInfo> {
        val slugs = HashSet<String>(favLeagues)
        favTeams.forEach { slugs += it.substringBefore(':') }
        if (favTeams.isNotEmpty()) leagues.filter { !isDomestic(it.slug) }.forEach { slugs += it.slug }
        return leagues.filter { it.slug in slugs }
    }

    private val boards = ConcurrentHashMap<String, Pair<Long, List<LiveMatch>>>()

    /**
     * One league's board for one ESPN day. (A date range makes the feed answer 400, so each day is asked on its
     * own.) Today's board is refreshed often (scores), the rest is kept for a while.
     */
    private suspend fun board(l: LeagueInfo, espnDay: LocalDate, fresh: Long): List<LiveMatch> {
        val key = l.slug + "|" + espnDay.format(day)
        boards[key]?.let { (at, list) -> if (System.currentTimeMillis() - at < fresh) return list }
        val j = get("$BASE/${l.slug}/scoreboard?dates=${espnDay.format(day)}") ?: return boards[key]?.second.orEmpty()
        j.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")
            ?.takeIf { it.isNotBlank() }?.let { leagueLogos[l.slug] = it }
        val list = parseEvents(j.optJSONArray("events"), l)
        boards[key] = System.currentTimeMillis() to list
        return list
    }

    /**
     * The matches of one local day for [leagueList]. ESPN counts days in US time, so a late-evening or after-midnight
     * kick-off here can sit on the day before: both days are asked, and the caller keeps the ones of its own day.
     */
    suspend fun fetchDay(localDay: LocalDate, leagueList: List<LeagueInfo>): List<LiveMatch> = coroutineScope {
        val today = LocalDate.now()
        val tasks = leagueList.flatMap { l -> listOf(localDay.minusDays(1), localDay).map { d ->
            async { runCatching { board(l, d, if (d >= today.minusDays(1) && d <= today) 60_000L else 15 * 60_000L) } }
        } }
        val results = tasks.map { it.await() }
        // Every request failing means the feed itself is unreachable: say so instead of showing "no matches".
        if (results.isNotEmpty() && results.all { it.isFailure }) throw (results.first().exceptionOrNull() ?: java.io.IOException("scores"))
        results.mapNotNull { it.getOrNull() }.flatten().distinctBy { it.id }.sortedBy { it.startMs }
    }

    private fun parseEvents(events: JSONArray?, l: LeagueInfo): List<LiveMatch> {
        if (events == null) return emptyList()
        return buildList {
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                val c = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                val teams = c.optJSONArray("competitors") ?: continue
                var home: JSONObject? = null
                var away: JSONObject? = null
                for (t in 0 until teams.length()) {
                    val team = teams.getJSONObject(t)
                    if (team.optString("homeAway") == "home") home = team else away = team
                }
                if (home == null || away == null) continue
                val ht = home.optJSONObject("team")
                val at = away.optJSONObject("team")
                // A team's schedule keeps the status and the scores in other places than the scoreboard does.
                val st = e.optJSONObject("status") ?: c.optJSONObject("status")
                val names = mutableListOf<String>()
                c.optJSONArray("broadcasts")?.let { b -> for (j in 0 until b.length()) b.getJSONObject(j).optJSONArray("names")?.let { n -> for (k in 0 until n.length()) names += n.getString(k) } }
                add(LiveMatch(
                    id = e.optString("id"), league = l.name, leagueSlug = l.slug,
                    homeId = ht?.optString("id").orEmpty(), awayId = at?.optString("id").orEmpty(),
                    home = ArabicNames.of(ht?.optString("shortDisplayName").orEmpty().ifBlank { ht?.optString("displayName").orEmpty() }),
                    away = ArabicNames.of(at?.optString("shortDisplayName").orEmpty().ifBlank { at?.optString("displayName").orEmpty() }),
                    homeLogo = logoOf(ht), awayLogo = logoOf(at),
                    homeScore = scoreOf(home), awayScore = scoreOf(away),
                    state = st?.optJSONObject("type")?.optString("state").orEmpty().ifBlank { "pre" },
                    clock = st?.optString("displayClock").orEmpty(),
                    startMs = runCatching { Instant.parse(e.optString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli() }.getOrDefault(0L),
                    broadcasts = names.distinct(),
                ))
            }
        }
    }

    private fun logoOf(t: JSONObject?): String? =
        t?.optString("logo")?.ifBlank { null } ?: t?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifBlank { null }

    /** The score is text on the scoreboard and an object ({"value", "displayValue"}) in a team's schedule. */
    private fun scoreOf(o: JSONObject): String = when (val v = o.opt("score")) {
        null -> "0"
        is JSONObject -> v.optString("displayValue", v.optString("value", "0"))
        else -> v.toString()
    }

    /** The domestic leagues: their clubs are the ones to choose from (a club also plays in the continental cups). */
    fun isDomestic(slug: String) = !slug.startsWith("uefa.") && !slug.startsWith("afc.")

    private val teamCache = ConcurrentHashMap<String, List<TeamInfo>>()

    /** Every team of a league (for choosing favourites and for club colours). */
    suspend fun teams(slug: String): List<TeamInfo> {
        teamCache[slug]?.let { return it }
        val arr = get("$BASE/$slug/teams?limit=100")?.optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams")
            ?: return emptyList()
        val out = buildList {
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i)?.optJSONObject("team") ?: continue
                add(TeamInfo(
                    id = t.optString("id"),
                    name = ArabicNames.of(t.optString("shortDisplayName").ifBlank { t.optString("displayName") }),
                    logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifBlank { null } ?: t.optString("logo").ifBlank { null },
                    colorHex = t.optString("color").ifBlank { null }?.let { "#$it" },
                    leagueSlug = slug,
                ))
            }
        }
        if (out.isNotEmpty()) teamCache[slug] = out
        return out
    }

    /** The league table. */
    suspend fun standings(slug: String): List<StandingRow> {
        val entries = get("https://site.api.espn.com/apis/v2/sports/soccer/$slug/standings")
            ?.optJSONArray("children")?.optJSONObject(0)?.optJSONObject("standings")?.optJSONArray("entries") ?: return emptyList()
        return buildList {
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val team = e.optJSONObject("team") ?: continue
                val stats = HashMap<String, Double>()
                e.optJSONArray("stats")?.let { s -> for (k in 0 until s.length()) s.optJSONObject(k)?.let { stats[it.optString("name")] = it.optDouble("value", 0.0) } }
                add(StandingRow(
                    pos = (stats["rank"] ?: (i + 1).toDouble()).toInt(), teamId = team.optString("id"),
                    team = ArabicNames.of(team.optString("shortDisplayName").ifBlank { team.optString("displayName") }),
                    logo = team.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifBlank { null },
                    played = (stats["gamesPlayed"] ?: 0.0).toInt(), won = (stats["wins"] ?: 0.0).toInt(),
                    drawn = (stats["ties"] ?: 0.0).toInt(), lost = (stats["losses"] ?: 0.0).toInt(),
                    diff = (stats["pointDifferential"] ?: 0.0).toInt(), points = (stats["points"] ?: 0.0).toInt(),
                ))
            }
        }.sortedBy { it.pos }
    }

    /** A team's matches: the finished ones from its schedule, and the coming one(s) from its page ("nextEvent"). */
    suspend fun teamMatches(slug: String, teamId: String): List<LiveMatch> {
        val l = league(slug) ?: return emptyList()
        val past = runCatching { get("$BASE/$slug/teams/$teamId/schedule")?.optJSONArray("events") }.getOrNull()
        val next = runCatching { get("$BASE/$slug/teams/$teamId")?.optJSONObject("team")?.optJSONArray("nextEvent") }.getOrNull()
        return (parseEvents(past, l) + parseEvents(next, l)).distinctBy { it.id }.sortedBy { it.startMs }
    }
}

/** Arabic names for the clubs people look for most; the rest show the feed's name. */
object ArabicNames {
    private val map = mapOf(
        "alhilal" to "الهلال", "alnassr" to "النصر", "alittihad" to "الاتحاد", "alahli" to "الأهلي", "alahlisaudi" to "الأهلي",
        "alshabab" to "الشباب", "alfateh" to "الفتح", "altaawoun" to "التعاون", "alraed" to "الرائد", "alkhaleej" to "الخليج",
        "alhazem" to "الحزم", "damac" to "ضمك", "alqadsiah" to "القادسية", "alfayha" to "الفيحاء", "alokhdood" to "الأخدود",
        "alriyadh" to "الرياض", "abha" to "أبها", "aldiriyah" to "الدرعية", "alfaisaly" to "الفيصلي", "neomsc" to "نيوم", "alkholood" to "الخلود", "alettifaq" to "الاتفاق", "alettifaqfc" to "الاتفاق", "neom" to "نيوم",
        "realmadrid" to "ريال مدريد", "barcelona" to "برشلونة", "atleticomadrid" to "أتلتيكو مدريد", "sevilla" to "إشبيلية",
        "valencia" to "فالنسيا", "villarreal" to "فياريال", "athleticclub" to "أتلتيك بلباو", "realsociedad" to "ريال سوسيداد",
        "realbetis" to "ريال بيتيس", "alaves" to "ألافيس", "girona" to "جيرونا",
        "manchestercity" to "مانشستر سيتي", "manchesterunited" to "مانشستر يونايتد", "liverpool" to "ليفربول", "arsenal" to "أرسنال",
        "chelsea" to "تشيلسي", "tottenhamhotspur" to "توتنهام", "tottenham" to "توتنهام", "newcastleunited" to "نيوكاسل",
        "astonvilla" to "أستون فيلا", "brighton" to "برايتون", "westhamunited" to "وست هام", "brentford" to "برينتفورد",
        "everton" to "إيفرتون", "fulham" to "فولهام", "crystalpalace" to "كريستال بالاس", "wolverhamptonwanderers" to "وولفرهامبتون",
        "bayernmunich" to "بايرن ميونخ", "bayernmünchen" to "بايرن ميونخ", "borussiadortmund" to "دورتموند", "bayerleverkusen" to "ليفركوزن",
        "rbleipzig" to "لايبزيغ", "hamburgsv" to "هامبورغ", "hamburger" to "هامبورغ",
        "parissaintgermain" to "باريس سان جيرمان", "psg" to "باريس سان جيرمان", "marseille" to "مارسيليا", "lyon" to "ليون",
        "monaco" to "موناكو", "lille" to "ليل", "lorient" to "لوريان",
        "juventus" to "يوفنتوس", "intermilan" to "إنتر ميلان", "internazionale" to "إنتر ميلان", "inter" to "إنتر ميلان",
        "acmilan" to "ميلان", "milan" to "ميلان", "napoli" to "نابولي", "roma" to "روما", "lazio" to "لاتسيو", "atalanta" to "أتالانتا",
        "benfica" to "بنفيكا", "porto" to "بورتو", "ajax" to "أياكس", "celtic" to "سلتيك",
    )

    fun of(name: String): String {
        if (name.isBlank()) return name
        val key = name.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "").removeSuffix("fc").removeSuffix("cf")
        return map[key] ?: map[name.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "")] ?: name
    }
}
