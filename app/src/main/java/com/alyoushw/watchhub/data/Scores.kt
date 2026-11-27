package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/** One football match for the home row. [state] is "in" (playing), "pre" (not started) or "post" (finished). */
data class LiveMatch(
    val id: String,
    val league: String,
    val leagueSlug: String,
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

/** League → name shown, and the channel names usually carrying it here (editable later; only a starting point). */
data class LeagueInfo(val slug: String, val name: String, val keywords: List<String>)

/** Today's matches from ESPN's public scoreboard (unofficial, no key). */
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

    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun fetch(): List<LiveMatch> = coroutineScope {
        leagues.map { l -> async { runCatching { league(l) }.getOrDefault(emptyList()) } }
            .map { it.await() }.flatten()
            .sortedWith(compareBy<LiveMatch> { when (it.state) { "in" -> 0; "pre" -> 1; else -> 2 } }.thenBy { it.startMs })
    }

    private suspend fun league(l: LeagueInfo): List<LiveMatch> = withContext(Dispatchers.IO) {
        val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/${l.slug}/scoreboard"
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { if (!it.isSuccessful) return@use null else it.body?.string() }
            ?: return@withContext emptyList()
        val events = JSONObject(body).optJSONArray("events") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                val c = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                val teams = c.optJSONArray("competitors") ?: continue
                var home: JSONObject? = null
                var away: JSONObject? = null
                for (t in 0 until teams.length()) {
                    val team = teams.getJSONObject(t)
                    if (team.optString("homeAway") == "home") home = team else away = team
                }
                if (home == null || away == null) continue
                val st = e.optJSONObject("status")
                val state = st?.optJSONObject("type")?.optString("state").orEmpty().ifBlank { "pre" }
                val names = mutableListOf<String>()
                c.optJSONArray("broadcasts")?.let { b -> for (j in 0 until b.length()) b.getJSONObject(j).optJSONArray("names")?.let { n -> for (k in 0 until n.length()) names += n.getString(k) } }
                add(LiveMatch(
                    id = e.optString("id"),
                    league = l.name, leagueSlug = l.slug,
                    home = home.optJSONObject("team")?.optString("shortDisplayName").orEmpty(),
                    away = away.optJSONObject("team")?.optString("shortDisplayName").orEmpty(),
                    homeLogo = home.optJSONObject("team")?.optString("logo")?.ifBlank { null },
                    awayLogo = away.optJSONObject("team")?.optString("logo")?.ifBlank { null },
                    homeScore = home.optString("score", "0"), awayScore = away.optString("score", "0"),
                    state = state,
                    clock = st?.optString("displayClock").orEmpty(),
                    startMs = runCatching { Instant.parse(e.optString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli() }.getOrDefault(0L),
                    broadcasts = names.distinct(),
                ))
            }
        }
    }
}
