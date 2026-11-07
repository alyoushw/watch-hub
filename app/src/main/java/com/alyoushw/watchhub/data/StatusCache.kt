package com.alyoushw.watchhub.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class StatusEntry(val status: String, val at: Long)

/**
 * Remembers each series' status (ongoing / ended …) for the poster badges.
 * Lookups are cached on disk: finished shows for a month, running ones for a day,
 * so scrolling a long list costs one small request per series at most.
 */
class StatusCache(context: Context, private val api: TmdbApi) {
    private val file = File(context.filesDir, "tv-status.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(Int.serializer(), StatusEntry.serializer())
    private val entries = ConcurrentHashMap<Int, StatusEntry>()
    private val inFlight = ConcurrentHashMap<Int, Deferred<String?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limiter = Semaphore(6)
    private var saveJob: Job? = null

    init {
        runCatching { entries.putAll(json.decodeFromString(serializer, file.readText())) }
    }

    /** The cached status if it is still fresh, without any network call. */
    fun cached(id: Int): String? = entries[id]?.takeIf { isFresh(it) }?.status

    suspend fun status(id: Int): String? {
        cached(id)?.let { return it }
        val job = inFlight.getOrPut(id) {
            scope.async {
                val status = limiter.withPermit {
                    try {
                        api.tvStatus(id)
                    } catch (_: ApiException) {
                        null
                    }
                }
                if (status != null) {
                    entries[id] = StatusEntry(status, System.currentTimeMillis())
                    scheduleSave()
                }
                inFlight.remove(id)
                status
            }
        }
        return job.await()
    }

    private fun isFresh(entry: StatusEntry): Boolean {
        val finished = entry.status == "Ended" || entry.status == "Canceled"
        val maxAge = if (finished) 30L * DAY else DAY
        return System.currentTimeMillis() - entry.at < maxAge
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(3000)
            runCatching { file.writeText(json.encodeToString(serializer, HashMap(entries))) }
        }
    }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000

        /** Arabic label and badge colour for a TMDB status. */
        fun label(status: String?): Pair<String, Long>? = when (status) {
            "Returning Series" -> "مستمر" to 0xFF2E7D32
            "Ended" -> "انتهى" to 0xFF5F6368
            "Canceled" -> "انلغى" to 0xFFB3261E
            "In Production", "Planned", "Pilot" -> "قريباً" to 0xFF1565C0
            else -> null
        }
    }
}
