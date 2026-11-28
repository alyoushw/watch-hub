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
    private class KRow(val home: String, val away: String, val kickoff: Long, val items: List<TChannel>)
    private class Day(val at: Long, val rows: List<YRow>, val kooora: List<KRow>)
    private val days = ConcurrentHashMap<String, Day>()
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private const val SERVER = "https://watch-hub-ddfg.onrender.com"
    private const val REFRESH = 15 * 60_000L

    private suspend fun day(d: LocalDate): Day? {
        val key = d.toString()
        days[key]?.let { if (System.currentTimeMillis() - it.at < REFRESH) return it }
        val fresh = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$SERVER/tv/day?date=$key").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val root = JSONObject(r.body?.string().orEmpty())
                    val arr = root.optJSONArray("rows") ?: return@use null
                    val rows = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val ch = o.optJSONArray("channels")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                        YRow(o.optString("home"), o.optString("away"), o.optLong("kickoff"), ch)
                    }
                    val ka = root.optJSONArray("kooora")
                    val koo = if (ka == null) emptyList() else (0 until ka.length()).mapNotNull { i ->
                        val o = ka.optJSONObject(i) ?: return@mapNotNull null
                        val it = o.optJSONArray("items")
                        val items = if (it == null) emptyList() else (0 until it.length()).mapNotNull { k -> it.optJSONObject(k)?.let { x -> TChannel(x.optString("channel"), x.optString("commentator")) } }
                        KRow(o.optString("home"), o.optString("away"), o.optLong("kickoff"), items)
                    }
                    Day(System.currentTimeMillis(), rows, koo)
                }
            }.getOrNull()
        }
        if (fresh != null) days[key] = fresh
        return fresh ?: days[key]
    }

    private fun ar(s: String): String = s.replace("ـ", "").replace(Regex("[\\u064B-\\u0652]"), "")
        .replace(Regex("[أإآ]"), "ا").replace('ى', 'ي').replace('ة', 'ه').lowercase().replace(Regex("[^a-z0-9\\u0621-\\u064a ]"), " ")
        .trim().replace(Regex("\\s+"), " ").removePrefix("ال").trim()

    private fun sameTeam(a: String, b: String): Boolean {
        // Spellings differ between sources (هامبورغ/هامبورج, ميونخ/ميونيخ): compare with غ=ج and without ي.
        val x = ar(a).replace('غ', 'ج').replace("ي", ""); val y = ar(b).replace('غ', 'ج').replace("ي", "")
        if (x.length < 3 || y.length < 3) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private class Found(val channels: List<String>, val items: List<TChannel>)

    /** Yallakora's channel(s) and Kooora's channel+commentator pairs for the match, matched by kick-off (±30 min) and teams. */
    private suspend fun fromServer(m: LiveMatch): Found {
        val zone = ZoneId.of("Asia/Riyadh")
        val d = Instant.ofEpochMilli(m.startMs).atZone(zone).toLocalDate()
        val ds = listOf(d, d.minusDays(1), d.plusDays(1)).mapNotNull { day(it) }
        fun score(h: String, a: String) = (if (sameTeam(h, m.home)) 1 else 0) + (if (sameTeam(a, m.away)) 1 else 0)
        val y = ds.flatMap { it.rows }.filter { kotlin.math.abs(it.kickoff - m.startMs) <= 30 * 60_000L }
            .map { it to score(it.home, it.away) }.filter { it.second >= 1 }.maxByOrNull { it.second }?.first
        val k = ds.flatMap { it.kooora }.filter { kotlin.math.abs(it.kickoff - m.startMs) <= 30 * 60_000L }
            .map { it to score(it.home, it.away) }.filter { it.second >= 1 }.maxByOrNull { it.second }?.first
        return Found(y?.channels.orEmpty(), k?.items.orEmpty())
    }

    private fun chanKey(s: String): String = s.lowercase()
        .replace("بى ان سبورت", "bein sports").replace("بي ان سبورت", "bein sports").replace("اكسترا", "xtra")
        .replace(Regex("\\bhd\\b"), "").replace(Regex("[^a-z0-9\\u0621-\\u064a]"), "")

    suspend fun channels(m: LiveMatch): List<TChannel> {
        val tifoId = runCatching { TifoApi.find(m) }.getOrNull()
        val fromTifo = tifoId?.let { runCatching { TifoApi.channels(it) }.getOrDefault(emptyList()) }.orEmpty().distinct()
        val f = runCatching { fromServer(m) }.getOrDefault(Found(emptyList(), emptyList()))
        val out = ArrayList<TChannel>(fromTifo)
        fun add(c: TChannel) {
            val i = out.indexOfFirst { chanKey(it.channel) == chanKey(c.channel) && (it.commentator == c.commentator || it.commentator.isBlank() || c.commentator.isBlank()) }
            if (i < 0) out += c else if (out[i].commentator.isBlank() && c.commentator.isNotBlank()) out[i] = c
        }
        f.items.filter { it.channel.isNotBlank() }.forEach(::add)
        f.channels.forEach { add(TChannel(it, "")) }
        return out
    }

    /** One line for the match list: «beIN SPORTS 1 · عبدالله الغامدي». */
    suspend fun line(m: LiveMatch): String? {
        val list = channels(m)
        if (list.isEmpty()) return null
        return list.joinToString("\n") { it.channel + (if (it.commentator.isNotBlank()) " · " + it.commentator else "") }
    }
}
