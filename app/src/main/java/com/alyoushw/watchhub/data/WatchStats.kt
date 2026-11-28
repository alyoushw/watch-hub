package com.alyoushw.watchhub.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth
import java.util.UUID

/** This month's numbers for "ملخص مشاهداتك". */
data class MonthStats(
    val totalMs: Long,
    val byKind: Map<String, Long>,
    val finished: Int,
    val topTitles: List<Pair<String, Long>>,
)

/**
 * Time actually spent watching, counted by the player while it plays (per month, per kind, per
 * title), and how many episodes / films were finished.
 *
 * Each device only ever adds to its OWN counters, and uploads them to the account as one small document
 * per device (see CloudSync). The summary adds up this device's counters and the other devices' latest
 * copies, so the totals are the same on every device and nothing is counted twice.
 */
class WatchStats(context: Context) {
    private val prefs = context.getSharedPreferences("watch_stats", Context.MODE_PRIVATE)
    /** The other devices' latest counters (device id → their JSON), as received from the account. */
    private val remote = context.getSharedPreferences("watch_stats_remote", Context.MODE_PRIVATE)

    /** Names this device's document in the account. */
    val deviceId: String = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    /** Set by the account sync: this device's counters changed and should be uploaded soon. */
    var onChange: (() -> Unit)? = null

    private fun month() = YearMonth.now().toString()

    fun record(kind: String, title: String, ms: Long) {
        val m = month()
        prefs.edit()
            .putLong("$m|kind|$kind", prefs.getLong("$m|kind|$kind", 0) + ms)
            .putLong("$m|title|$title", prefs.getLong("$m|title|$title", 0) + ms)
            .apply()
        onChange?.invoke()
    }

    fun recordFinished(key: String) {
        val m = month()
        val done = prefs.getStringSet("$m|done", emptySet()).orEmpty()
        if (key in done) return
        prefs.edit().putStringSet("$m|done", done + key).apply()
        onChange?.invoke()
    }

    /** This device's counters for this month and the one before, as one JSON text for the account. */
    fun exportMine(): String {
        val all = prefs.all
        val out = JSONObject()
        for (m in listOf(YearMonth.now(), YearMonth.now().minusMonths(1)).map { it.toString() }) {
            val kind = JSONObject()
            val title = JSONObject()
            all.forEach { (k, v) ->
                if (v is Long) {
                    if (k.startsWith("$m|kind|")) kind.put(k.removePrefix("$m|kind|"), v)
                    else if (k.startsWith("$m|title|")) title.put(k.removePrefix("$m|title|"), v)
                }
            }
            out.put(m, JSONObject().put("kind", kind).put("title", title)
                .put("done", JSONArray(prefs.getStringSet("$m|done", emptySet()).orEmpty().toList())))
        }
        return out.toString()
    }

    /** Another device's counters from the account ([json] null: that device's copy was removed). */
    fun setRemote(deviceId: String, json: String?) {
        if (deviceId == this.deviceId) return
        if (json == null) remote.edit().remove(deviceId).apply() else remote.edit().putString(deviceId, json).apply()
    }

    /**
     * The counters belong to the account that was signed in while watching, like the history: this puts [from]'s
     * counters aside and brings [to]'s back (empty if none), so one account's minutes never land in another's summary.
     */
    @Synchronized
    fun switchProfile(from: String, to: String) {
        val current = prefs.all.filterKeys { it != KEY_DEVICE && !it.startsWith("@") }
        val stashed = prefs.all.filterKeys { it.startsWith("@$to|") }
        val e = prefs.edit()
        current.forEach { (k, v) -> e.remove(k); put(e, "@$from|$k", v) }
        stashed.forEach { (k, v) -> e.remove(k); put(e, k.removePrefix("@$to|"), v) }
        e.commit()
    }

    @Suppress("UNCHECKED_CAST")
    private fun put(e: android.content.SharedPreferences.Editor, key: String, v: Any?) {
        when (v) {
            is Long -> e.putLong(key, v)
            is Set<*> -> e.putStringSet(key, v as Set<String>)
            is String -> e.putString(key, v)
        }
    }

    /** Forgets the other devices' copies (when the account on this device changes). */
    fun clearRemote() {
        remote.edit().clear().apply()
    }

    fun thisMonth(): MonthStats {
        val m = month()
        val kinds = mutableMapOf<String, Long>()
        val titles = mutableMapOf<String, Long>()
        val done = mutableSetOf<String>()
        prefs.all.forEach { (k, v) ->
            if (v !is Long) return@forEach
            if (k.startsWith("$m|kind|")) kinds.merge(k.removePrefix("$m|kind|"), v, Long::plus)
            else if (k.startsWith("$m|title|")) titles.merge(k.removePrefix("$m|title|"), v, Long::plus)
        }
        done += prefs.getStringSet("$m|done", emptySet()).orEmpty()
        remote.all.forEach { (id, raw) ->
            if (id == deviceId) return@forEach
            val month = runCatching { JSONObject(raw as String).optJSONObject(m) }.getOrNull() ?: return@forEach
            month.optJSONObject("kind")?.let { o -> o.keys().forEach { k -> kinds.merge(k, o.optLong(k), Long::plus) } }
            month.optJSONObject("title")?.let { o -> o.keys().forEach { k -> titles.merge(k, o.optLong(k), Long::plus) } }
            month.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) done += a.optString(i) }
        }
        val top = titles.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
        return MonthStats(kinds.values.sum(), kinds, done.size, top)
    }

    private companion object {
        const val KEY_DEVICE = "device_id"
    }
}
