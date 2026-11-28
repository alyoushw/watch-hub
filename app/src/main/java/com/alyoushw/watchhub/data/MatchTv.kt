package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The channel(s) and commentator(s) of one match, merged from two live sources — nothing is typed in by hand and nothing is
 * assumed from a competition's rights:
 *  - Tifo's TV table (channel + commentator, matched by Tifo match id);
 *  - Yallakora's public match centre, read through the Watch Hub server (one shared cache, refreshed every 15 minutes), which
 *    gives the channel of each match (also Thmanyah for the Saudi league) and is matched by kick-off time and team names.
 * The last good answer is kept when a refresh fails.
 */
object MatchTv {
    private class YRow(val home: String, val away: String, val kickoff: Long, val channels: List<String>)
    private class Day(val at: Long, val rows: List<YRow>)
    private val days = ConcurrentHashMap<String, Day>()
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private const val SERVER = "https://watch-hub-ddfg.onrender.com"
    private const val REFRESH = 15 * 60_000L

    private suspend fun day(d: LocalDate): List<YRow> {
        val key = d.toString()
        days[key]?.let { if (System.currentTimeMillis() - it.at < REFRESH) return it.rows }
        val fresh = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$SERVER/tv/day?date=$key").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("rows") ?: return@use null
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val ch = o.optJSONArray("channels")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                        YRow(o.optString("home"), o.optString("away"), o.optLong("kickoff"), ch)
                    }
                }
            }.getOrNull()
        }
        if (fresh != null) days[key] = Day(System.currentTimeMillis(), fresh)
        return fresh ?: days[key]?.rows.orEmpty()
    }

    private fun ar(s: String): String = s.replace("ـ", "").replace(Regex("[\\u064B-\\u0652]"), "")
        .replace(Regex("[أإآ]"), "ا").replace('ى', 'ي').replace('ة', 'ه').lowercase().replace(Regex("[^a-z0-9\\u0621-\\u064a ]"), " ")
        .trim().replace(Regex("\\s+"), " ").removePrefix("ال").trim()

    private fun sameTeam(a: String, b: String): Boolean {
        val x = ar(a); val y = ar(b)
        if (x.length < 3 || y.length < 3) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    /** The channel of the match as Yallakora lists it (several are possible), matched by kick-off (±30 min) and teams. */
    private suspend fun yallakora(m: LiveMatch): List<String> {
        val zone = ZoneId.of("Asia/Riyadh")
        val d = Instant.ofEpochMilli(m.startMs).atZone(zone).toLocalDate()
        val rows = (listOf(d, d.minusDays(1), d.plusDays(1))).flatMap { day(it) }
        val best = rows.filter { kotlin.math.abs(it.kickoff - m.startMs) <= 30 * 60_000L }
            .map { r -> r to ((if (sameTeam(r.home, m.home)) 1 else 0) + (if (sameTeam(r.away, m.away)) 1 else 0)) }
            .filter { it.second >= 1 }.maxByOrNull { it.second }?.first
        return best?.channels.orEmpty()
    }

    private fun chanKey(s: String): String = s.lowercase()
        .replace("بى ان سبورت", "bein sports").replace("بي ان سبورت", "bein sports").replace("اكسترا", "xtra")
        .replace(Regex("\\bhd\\b"), "").replace(Regex("[^a-z0-9\\u0621-\\u064a]"), "")

    suspend fun channels(m: LiveMatch): List<TChannel> {
        val tifoId = runCatching { TifoApi.find(m) }.getOrNull()
        val fromTifo = tifoId?.let { runCatching { TifoApi.channels(it) }.getOrDefault(emptyList()) }.orEmpty().distinct()
        val fromY = runCatching { yallakora(m) }.getOrDefault(emptyList())
        val have = fromTifo.map { chanKey(it.channel) }.toSet()
        return fromTifo + fromY.filter { chanKey(it) !in have }.distinctBy { chanKey(it) }.map { TChannel(it, "") }
    }

    /** One line for the match list: «beIN SPORTS 1 · عبدالله الغامدي». */
    suspend fun line(m: LiveMatch): String? {
        val list = channels(m)
        if (list.isEmpty()) return null
        return list.joinToString(" | ") { it.channel + (if (it.commentator.isNotBlank()) " · " + it.commentator else "") }
    }
}
