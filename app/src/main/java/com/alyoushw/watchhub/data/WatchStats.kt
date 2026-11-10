package com.alyoushw.watchhub.data

import android.content.Context
import java.time.YearMonth

/** This month's numbers for "ملخص مشاهداتك". */
data class MonthStats(
    val totalMs: Long,
    val byKind: Map<String, Long>,
    val finished: Int,
    val topTitles: List<Pair<String, Long>>,
)

/**
 * Time actually spent watching, counted by the player while it plays (per month, per kind, per
 * title), and how many episodes / films were finished. Kept on the device.
 */
class WatchStats(context: Context) {
    private val prefs = context.getSharedPreferences("watch_stats", Context.MODE_PRIVATE)

    private fun month() = YearMonth.now().toString()

    fun record(kind: String, title: String, ms: Long) {
        val m = month()
        prefs.edit()
            .putLong("$m|kind|$kind", prefs.getLong("$m|kind|$kind", 0) + ms)
            .putLong("$m|title|$title", prefs.getLong("$m|title|$title", 0) + ms)
            .apply()
    }

    fun recordFinished(key: String) {
        val m = month()
        val done = prefs.getStringSet("$m|done", emptySet()).orEmpty()
        if (key in done) return
        prefs.edit().putStringSet("$m|done", done + key).apply()
    }

    fun thisMonth(): MonthStats {
        val m = month()
        val all = prefs.all
        val byKind = all.filterKeys { it.startsWith("$m|kind|") }.mapKeys { it.key.removePrefix("$m|kind|") }.mapValues { (it.value as? Long) ?: 0L }
        val titles = all.filterKeys { it.startsWith("$m|title|") }.map { it.key.removePrefix("$m|title|") to ((it.value as? Long) ?: 0L) }
            .sortedByDescending { it.second }.take(3)
        return MonthStats(byKind.values.sum(), byKind, prefs.getStringSet("$m|done", emptySet()).orEmpty().size, titles)
    }
}
