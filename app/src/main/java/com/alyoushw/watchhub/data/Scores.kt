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
    val goals: List<GoalEvent> = emptyList(),
)

/** One goal of a match, from the feed's match details ("scoringPlay"). */
data class GoalEvent(val teamId: String, val player: String, val minute: String, val penalty: Boolean, val own: Boolean)

/** One row of the match statistics: [label] (Arabic), both sides' values as shown, and the share of the first side. */
data class MatchStat(val label: String, val home: String, val away: String, val homeShare: Float)

/** One event of the match timeline (goal, card, substitution). */
data class TimelineEvent(val minute: String, val kind: String, val teamId: String, val player: String, val other: String?)

data class LineupPlayer(val number: String, val name: String, val position: String, val starter: Boolean)
data class Lineup(val teamId: String, val team: String, val formation: String, val players: List<LineupPlayer>)

/** Everything the match page shows besides the score. */
data class MatchDetails(
    val venue: String?, val attendance: String?,
    val stats: List<MatchStat>, val events: List<TimelineEvent>, val lineups: List<Lineup>,
    /** team id → its last matches as "W/D/L opponent score". */
    val form: Map<String, List<String>>, val h2hSummary: String?, val h2h: List<String>,
)

data class Leader(val player: String, val teamId: String, val value: Int)
data class Player(val id: String, val name: String, val number: String, val position: String, val age: String, val country: String)

/** League → name shown, and the channel names usually carrying it here (only a starting point). */
data class LeagueInfo(val slug: String, val name: String, val keywords: List<String>)

data class TeamInfo(val id: String, val name: String, val logo: String?, val colorHex: String?, val leagueSlug: String)

data class StandingRow(
    val pos: Int, val teamId: String, val team: String, val logo: String?,
    val played: Int, val won: Int, val drawn: Int, val lost: Int, val diff: Int, val points: Int,
    val goalsFor: Int = 0, val goalsAgainst: Int = 0,
)

/** Matches, tables and teams from ESPN's public football feed (unofficial, no key). */
object ScoresApi {
    val leagues = listOf(
        LeagueInfo("ksa.1", "الدوري السعودي", listOf("ثمانية")),
        LeagueInfo("uefa.champions", "دوري أبطال أوروبا", listOf("bein")),
        LeagueInfo("eng.1", "الدوري الإنجليزي", listOf("bein")),
        LeagueInfo("esp.1", "الدوري الإسباني", listOf("bein")),
        LeagueInfo("ita.1", "الدوري الإيطالي", listOf("bein")),
        LeagueInfo("ger.1", "الدوري الألماني", listOf("bein")),
        LeagueInfo("fra.1", "الدوري الفرنسي", listOf("bein")),
        LeagueInfo("uefa.europa", "الدوري الأوروبي", listOf("bein")),
        LeagueInfo("afc.champions", "دوري أبطال آسيا", listOf("ثمانية", "bein")),
    )

    /** The cups played next to the leagues (shown only for the clubs you chose). */
    val cups = listOf(
        LeagueInfo("ksa.kings.cup", "كأس خادم الحرمين الشريفين", listOf("ثمانية")),
        LeagueInfo("afc.cup", "دوري أبطال آسيا 2", listOf("ثمانية", "bein")),
        LeagueInfo("fifa.intercontinental_cup", "كأس الإنتركونتيننتال", listOf("bein")),
        LeagueInfo("uefa.europa.conf", "دوري المؤتمر الأوروبي", listOf("bein")),
        LeagueInfo("eng.fa", "كأس الاتحاد الإنجليزي", listOf("bein")),
        LeagueInfo("eng.league_cup", "كأس الرابطة الإنجليزية", listOf("bein")),
        LeagueInfo("ita.coppa_italia", "كأس إيطاليا", listOf("bein")),
        LeagueInfo("esp.copa_del_rey", "كأس ملك إسبانيا", listOf("bein")),
        LeagueInfo("ger.dfb_pokal", "كأس ألمانيا", listOf("bein")),
        LeagueInfo("fra.coupe_de_france", "كأس فرنسا", listOf("bein")),
    )

    fun league(slug: String): LeagueInfo? = leagues.firstOrNull { it.slug == slug } ?: cups.firstOrNull { it.slug == slug }

    /** League crests the feed gave us (filled while loading matches). */
    val leagueLogos = ConcurrentHashMap<String, String>()

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val ym = DateTimeFormatter.ofPattern("yyyyMM")

    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()?.let { JSONObject(it) }
        }
    }

    /**
     * Which competitions are worth asking: the chosen leagues, the leagues of the chosen clubs, and — when clubs are
     * chosen — the cups and continental games those clubs play (Saudi clubs: the King's Cup and Asia; the others: Europe
     * and their own cups).
     */
    fun relevant(favLeagues: Set<String>, favTeams: Set<String>): List<LeagueInfo> {
        val slugs = HashSet<String>(favLeagues)
        favTeams.forEach { slugs += it.substringBefore(':') }
        val countries = slugs.map { it.substringBefore('.') }.toSet()
        val out = leagues.filter { it.slug in slugs }.toMutableList()
        if (favTeams.isNotEmpty()) {
            out += leagues.filter { it.slug !in slugs && !isDomestic(it.slug) && (if ("ksa" in countries) it.slug.startsWith("afc.") else it.slug.startsWith("uefa.")) }
            out += cups.filter { it.slug.substringBefore('.') in countries || (it.slug.startsWith("uefa.") && countries.any { c -> c != "ksa" }) || (it.slug.startsWith("fifa.") && "ksa" in countries) }
        }
        return out.distinctBy { it.slug }
    }

    private class Board(val at: Long, val ttl: Long, val list: List<LiveMatch>)
    private val boards = ConcurrentHashMap<String, Board>()

    /**
     * One competition's board for a whole month (the feed refuses date ranges but gives a month in one answer). A board with
     * a match on now is asked again every minute (scores); the others are kept for a quarter of an hour.
     */
    private suspend fun board(l: LeagueInfo, month: String): List<LiveMatch> {
        val key = l.slug + "|" + month
        boards[key]?.let { b -> if (System.currentTimeMillis() - b.at < b.ttl) return b.list }
        val j = get("$BASE/${l.slug}/scoreboard?dates=$month&limit=500") ?: return boards[key]?.list.orEmpty()
        j.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")
            ?.takeIf { it.isNotBlank() }?.let { leagueLogos[l.slug] = it }
        val list = parseEvents(j.optJSONArray("events"), l)
        val now = System.currentTimeMillis()
        val live = list.any { it.state == "in" || kotlin.math.abs(it.startMs - now) < 3 * 3600_000L }
        boards[key] = Board(now, if (live) 60_000L else 15 * 60_000L, list)
        return list
    }

    /** Every match of the months that hold the next [days] days, for [leagueList]. */
    suspend fun fetchMonths(days: Int, leagueList: List<LeagueInfo>): List<LiveMatch> = coroutineScope {
        val today = LocalDate.now()
        val months = listOf(today.minusDays(1), today.plusDays(days.toLong())).map { it.format(ym) }.distinct()
        val tasks = leagueList.flatMap { l -> months.map { m -> async { runCatching { board(l, m) } } } }
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
                val goals = mutableListOf<GoalEvent>()
                c.optJSONArray("details")?.let { d ->
                    for (g in 0 until d.length()) {
                        val x = d.optJSONObject(g) ?: continue
                        if (!x.optBoolean("scoringPlay") || x.optBoolean("shootout")) continue
                        val who = x.optJSONArray("athletesInvolved")?.optJSONObject(0)
                        goals += GoalEvent(
                            x.optJSONObject("team")?.optString("id").orEmpty(),
                            who?.optString("shortName")?.ifBlank { null } ?: who?.optString("displayName").orEmpty(),
                            x.optJSONObject("clock")?.optString("displayValue").orEmpty(), x.optBoolean("penaltyKick"), x.optBoolean("ownGoal"),
                        )
                    }
                }
                add(LiveMatch(
                    id = e.optString("id"), league = KulliqNames.competitions[l.slug]?.first ?: l.name, leagueSlug = l.slug,
                    homeId = ht?.optString("id").orEmpty(), awayId = at?.optString("id").orEmpty(),
                    home = ArabicNames.of(ht?.optString("shortDisplayName").orEmpty().ifBlank { ht?.optString("displayName").orEmpty() }, ht?.optString("id")),
                    away = ArabicNames.of(at?.optString("shortDisplayName").orEmpty().ifBlank { at?.optString("displayName").orEmpty() }, at?.optString("id")),
                    homeLogo = logoOf(ht), awayLogo = logoOf(at),
                    homeScore = scoreOf(home), awayScore = scoreOf(away),
                    state = st?.optJSONObject("type")?.optString("state").orEmpty().ifBlank { "pre" },
                    clock = st?.optString("displayClock").orEmpty(),
                    startMs = runCatching { Instant.parse(e.optString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli() }.getOrDefault(0L),
                    broadcasts = names.distinct(), goals = goals,
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
                    name = ArabicNames.of(t.optString("shortDisplayName").ifBlank { t.optString("displayName") }, t.optString("id")),
                    logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifBlank { null } ?: t.optString("logo").ifBlank { null },
                    colorHex = t.optString("color").ifBlank { null }?.let { "#$it" },
                    leagueSlug = slug,
                ))
            }
        }
        if (out.isNotEmpty()) teamCache[slug] = out
        return out
    }

    /** The domestic league of a club (a cup's page lists the club under the cup, but its table and squad are in its league). */
    suspend fun homeSlug(teamId: String, hint: String): String {
        if (isDomestic(hint)) return hint
        for (l in leagues.filter { isDomestic(it.slug) }) if (runCatching { teams(l.slug) }.getOrDefault(emptyList()).any { it.id == teamId }) return l.slug
        return hint
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
                    team = ArabicNames.of(team.optString("shortDisplayName").ifBlank { team.optString("displayName") }, team.optString("id")),
                    logo = team.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifBlank { null },
                    played = (stats["gamesPlayed"] ?: 0.0).toInt(), won = (stats["wins"] ?: 0.0).toInt(),
                    drawn = (stats["ties"] ?: 0.0).toInt(), lost = (stats["losses"] ?: 0.0).toInt(),
                    diff = (stats["pointDifferential"] ?: 0.0).toInt(), points = (stats["points"] ?: 0.0).toInt(),
                    goalsFor = (stats["pointsFor"] ?: 0.0).toInt(), goalsAgainst = (stats["pointsAgainst"] ?: 0.0).toInt(),
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

    private val STAT_LABELS = listOf(
        "possessionPct" to "الاستحواذ %", "totalShots" to "التسديدات", "shotsOnTarget" to "على المرمى", "blockedShots" to "تسديدات محجوبة",
        "wonCorners" to "الركنيات", "foulsCommitted" to "الأخطاء", "offsides" to "التسلل", "yellowCards" to "البطاقات الصفراء",
        "redCards" to "البطاقات الحمراء", "saves" to "التصديات", "accuratePasses" to "التمريرات الدقيقة", "totalPasses" to "التمريرات",
        "totalTackles" to "الالتحامات", "effectiveTackles" to "التحامات ناجحة", "totalClearance" to "إبعادات",
    )
    private val EVENT_KINDS = mapOf(
        "Goal" to "هدف", "Own Goal" to "هدف عكسي", "Penalty - Scored" to "ركلة جزاء (هدف)", "Penalty - Missed" to "ركلة جزاء ضائعة", "Penalty - Saved" to "ركلة جزاء مصدّة",
        "Yellow Card" to "بطاقة صفراء", "Red Card" to "بطاقة حمراء", "Substitution" to "تبديل", "Goal - Header" to "هدف برأسية", "Goal - Free-kick" to "هدف من ركلة حرة",
    )
    private val detailCache = ConcurrentHashMap<String, Pair<Long, MatchDetails>>()

    /** The match page's data: statistics, timeline, line-ups, form and head-to-head (ESPN's "summary"). */
    suspend fun details(slug: String, eventId: String, homeId: String, finished: Boolean): MatchDetails? {
        val key = "$slug|$eventId"
        detailCache[key]?.let { (at, d) -> if (System.currentTimeMillis() - at < (if (finished) 30 * 60_000L else 45_000L)) return d }
        val j = get("$BASE/$slug/summary?event=$eventId") ?: return null
        val stats = ArrayList<MatchStat>()
        val teams = j.optJSONObject("boxscore")?.optJSONArray("teams")
        if (teams != null && teams.length() >= 2) {
            fun byTeam(id: String): Map<String, String> {
                val out = HashMap<String, String>()
                for (i in 0 until teams.length()) {
                    val t = teams.getJSONObject(i)
                    if (t.optJSONObject("team")?.optString("id") != id) continue
                    t.optJSONArray("statistics")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).let { out[it.optString("name")] = it.optString("displayValue") } }
                }
                return out
            }
            val ids = (0 until teams.length()).map { teams.getJSONObject(it).optJSONObject("team")?.optString("id").orEmpty() }
            val hid = ids.firstOrNull { it == homeId } ?: ids[0]
            val aid = ids.firstOrNull { it != hid } ?: ids[1]
            val h = byTeam(hid); val a = byTeam(aid)
            for ((k, label) in STAT_LABELS) {
                val hv = h[k]; val av = a[k]
                if (hv == null && av == null) continue
                val hn = hv?.toFloatOrNull() ?: 0f; val an = av?.toFloatOrNull() ?: 0f
                val share = if (hn + an <= 0f) 0.5f else hn / (hn + an)
                val suffix = if (k == "possessionPct") "%" else ""
                stats += MatchStat(label, (hv ?: "0") + suffix, (av ?: "0") + suffix, share)
            }
        }
        val events = ArrayList<TimelineEvent>()
        j.optJSONArray("keyEvents")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                val kind = EVENT_KINDS[e.optJSONObject("type")?.optString("text").orEmpty()] ?: continue
                val ps = e.optJSONArray("participants")
                fun nm(n: Int) = ps?.optJSONObject(n)?.optJSONObject("athlete")?.optString("displayName")?.ifBlank { null }
                events += TimelineEvent(e.optJSONObject("clock")?.optString("displayValue").orEmpty(), kind, e.optJSONObject("team")?.optString("id").orEmpty(), nm(0).orEmpty(), nm(1))
            }
        }
        val lineups = ArrayList<Lineup>()
        j.optJSONArray("rosters")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                val players = ArrayList<LineupPlayer>()
                r.optJSONArray("roster")?.let { ps -> for (k in 0 until ps.length()) {
                    val p = ps.getJSONObject(k)
                    players += LineupPlayer(p.optString("jersey"), p.optJSONObject("athlete")?.optString("shortName")?.ifBlank { null } ?: p.optJSONObject("athlete")?.optString("displayName").orEmpty(),
                        p.optJSONObject("position")?.optString("abbreviation").orEmpty(), p.optBoolean("starter"))
                } }
                val t = r.optJSONObject("team")
                lineups += Lineup(t?.optString("id").orEmpty(), ArabicNames.of(t?.optString("shortDisplayName").orEmpty().ifBlank { t?.optString("displayName").orEmpty() }, t?.optString("id")), r.optString("formation"), players)
            }
        }
        val form = HashMap<String, List<String>>()
        j.optJSONArray("lastFiveGames")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i)
                val id = t.optJSONObject("team")?.optString("id").orEmpty()
                val ev = t.optJSONArray("events") ?: continue
                form[id] = (0 until ev.length()).map { k ->
                    val g = ev.getJSONObject(k)
                    val opp = g.optJSONObject("opponent")
                    g.optString("gameResult") + " " + ArabicNames.of(opp?.optString("shortDisplayName").orEmpty().ifBlank { opp?.optString("displayName").orEmpty() }, opp?.optString("id")) + " " + g.optString("score")
                }
            }
        }
        var h2hSummary: String? = null
        val h2h = ArrayList<String>()
        j.optJSONArray("seasonseries")?.optJSONObject(0)?.let { ss ->
            h2hSummary = ss.optString("summary").ifBlank { null }
            ss.optJSONArray("events")?.let { ev ->
                for (i in 0 until ev.length()) {
                    val g = ev.getJSONObject(i)
                    val cs = g.optJSONArray("competitors") ?: continue
                    val line = (0 until cs.length()).map { k -> cs.getJSONObject(k) }.sortedBy { if (it.optString("homeAway") == "home") 0 else 1 }
                        .joinToString(" - ") { c -> ArabicNames.of(c.optJSONObject("team")?.optString("shortDisplayName").orEmpty().ifBlank { c.optJSONObject("team")?.optString("displayName").orEmpty() }, c.optJSONObject("team")?.optString("id")) + " " + c.optString("score") }
                    h2h += g.optString("date").take(10) + " · " + line
                }
            }
        }
        val gi = j.optJSONObject("gameInfo")
        val d = MatchDetails(gi?.optJSONObject("venue")?.optString("fullName")?.ifBlank { null }, gi?.optInt("attendance", 0)?.takeIf { it > 0 }?.toString(), stats, events, lineups, form, h2hSummary, h2h)
        detailCache[key] = System.currentTimeMillis() to d
        return d
    }

    private fun seasonYear(): Int = LocalDate.now().let { if (it.monthValue >= 7) it.year else it.year - 1 }
    private val leaderCache = ConcurrentHashMap<String, Pair<Long, List<Leader>>>()
    private val nameCache = ConcurrentHashMap<String, String>()

    /** The league's top scorers ("goalsLeaders") or top assist makers ("assistsLeaders"): player, club and number. */
    suspend fun leaders(slug: String, category: String, limit: Int = 10): List<Leader> = coroutineScope {
        val ck = "$slug|$category"
        leaderCache[ck]?.let { (at, l) -> if (System.currentTimeMillis() - at < 60 * 60_000L) return@coroutineScope l }
        val year = seasonYear()
        val base = "https://sports.core.api.espn.com/v2/sports/soccer/leagues/$slug/seasons/$year"
        val cat = get("$base/types/1/leaders")?.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name") == category } }
        val arr = cat?.optJSONArray("leaders") ?: return@coroutineScope emptyList()
        val idOf = Regex("/(?:athletes|teams)/(\\d+)")
        val picked = (0 until minOf(limit, arr.length())).map { arr.getJSONObject(it) }
        val out = picked.map { l ->
            async {
                val aid = idOf.find(l.optJSONObject("athlete")?.optString("\$ref").orEmpty())?.groupValues?.get(1).orEmpty()
                val tid = idOf.find(l.optJSONObject("team")?.optString("\$ref").orEmpty())?.groupValues?.get(1).orEmpty()
                val name = nameCache[aid] ?: runCatching { get("$base/athletes/$aid")?.let { it.optString("displayName").ifBlank { it.optString("fullName") } } }.getOrNull().orEmpty().also { if (it.isNotBlank()) nameCache[aid] = it }
                Leader(name, tid, l.optDouble("value", 0.0).toInt())
            }
        }.map { it.await() }.filter { it.player.isNotBlank() }
        if (out.isNotEmpty()) leaderCache[ck] = System.currentTimeMillis() to out
        out
    }

    private val rosterCache = ConcurrentHashMap<String, List<Player>>()

    /** A club's players (ESPN's roster), for the squad by position. */
    suspend fun roster(slug: String, teamId: String): List<Player> {
        rosterCache["$slug|$teamId"]?.let { return it }
        val arr = get("$BASE/$slug/teams/$teamId/roster")?.optJSONArray("athletes") ?: return emptyList()
        val out = buildList {
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                add(Player(a.optString("id"), a.optString("displayName").ifBlank { a.optString("fullName") }, a.optString("jersey"),
                    a.optJSONObject("position")?.optString("displayName").orEmpty(), a.optString("age"), a.optString("citizenship")))
            }
        }
        if (out.isNotEmpty()) rosterCache["$slug|$teamId"] = out
        return out
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

    fun of(name: String, id: String? = null): String {
        if (name.isBlank()) return name
        if (!id.isNullOrBlank()) KulliqNames.teams[id]?.let { return it }
        val key = name.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "").removeSuffix("fc").removeSuffix("cf")
        return map[key] ?: map[name.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "")] ?: name
    }
}
