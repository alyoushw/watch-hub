package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Everything the user saves lives in one small JSON file on the device. */
class LocalStore(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val mutex = Mutex()
    private val _data = MutableStateFlow(load())
    val data: StateFlow<StoreData> = _data.asStateFlow()

    private fun load(): StoreData = runCatching {
        if (file.exists()) json.decodeFromString<StoreData>(file.readText()) else StoreData()
    }.getOrDefault(StoreData())

    suspend fun update(transform: (StoreData) -> StoreData) = mutex.withLock {
        val next = transform(_data.value)
        _data.value = next
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(StoreData.serializer(), next))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    /**
     * Called after the user changes the watchlist on this device (added, status or episode
     * changed, removed), for the account sync. [item] null means [key] was removed at [removedAt].
     */
    var onWatchChanged: ((item: WatchItem?, key: String, removedAt: Long?) -> Unit)? = null

    /** What other devices should see change: adding, removing, the status or the last episode. */
    private fun userVisibleChange(old: WatchItem?, new: WatchItem) =
        old == null || old.status != new.status || old.progress != new.progress

    suspend fun upsertWatch(item: WatchItem) {
        var changed: WatchItem? = null
        update { d ->
            val old = d.watchlist.firstOrNull { it.key == item.key }
            val stamped = if (userVisibleChange(old, item)) item.copy(updatedAt = System.currentTimeMillis()).also { changed = it }
            else item.copy(updatedAt = old?.updatedAt ?: item.updatedAt)
            d.copy(
                watchlist = if (old != null) d.watchlist.map { if (it.key == item.key) stamped else it } else d.watchlist + stamped,
                removedWatch = d.removedWatch - item.key,
            )
        }
        changed?.let { onWatchChanged?.invoke(it, it.key, null) }
    }

    suspend fun updateWatch(key: String, transform: (WatchItem) -> WatchItem) {
        var changed: WatchItem? = null
        update { d ->
            d.copy(
                watchlist = d.watchlist.map { old ->
                    if (old.key != key) return@map old
                    val new = transform(old)
                    if (userVisibleChange(old, new)) new.copy(updatedAt = System.currentTimeMillis()).also { changed = it } else new
                },
            )
        }
        changed?.let { onWatchChanged?.invoke(it, it.key, null) }
    }

    suspend fun removeWatch(key: String) {
        val now = System.currentTimeMillis()
        update { d ->
            d.copy(
                watchlist = d.watchlist.filterNot { it.key == key },
                removedWatch = (d.removedWatch + (key to now)).entries.sortedByDescending { it.value }.take(300).associate { it.key to it.value },
            )
        }
        onWatchChanged?.invoke(null, key, now)
    }

    /**
     * A watchlist change from another device, kept when newer than this device's copy.
     * [item] null means it was removed at [updatedAt]. Nothing is sent back.
     */
    suspend fun applyRemoteWatch(key: String, item: WatchItem?, updatedAt: Long) = update { d ->
        val local = d.watchlist.firstOrNull { it.key == key }
        val removedHere = d.removedWatch[key]
        when {
            local != null && local.updatedAt >= updatedAt -> d
            local == null && removedHere != null && removedHere >= updatedAt -> d
            item == null -> d.copy(
                watchlist = d.watchlist.filterNot { it.key == key },
                removedWatch = d.removedWatch + (key to updatedAt),
            )
            else -> {
                // Keep this device's extra details (seasons, links…) and take the synced choices.
                val merged = (local ?: item).copy(
                    title = item.title.ifBlank { local?.title.orEmpty() },
                    posterPath = item.posterPath ?: local?.posterPath,
                    status = item.status,
                    progress = item.progress ?: local?.progress,
                    seasons = item.seasons.ifEmpty { local?.seasons.orEmpty() },
                    addedAt = item.addedAt,
                    updatedAt = updatedAt,
                )
                d.copy(
                    watchlist = if (local != null) d.watchlist.map { if (it.key == key) merged else it } else d.watchlist + merged,
                    removedWatch = d.removedWatch - key,
                )
            }
        }
    }

    suspend fun upsertSubscription(sub: Subscription) = update { d ->
        val exists = d.subscriptions.any { it.id == sub.id }
        d.copy(
            subscriptions = if (exists) d.subscriptions.map { if (it.id == sub.id) sub else it }
            else d.subscriptions + sub,
        )
    }

    suspend fun saveFilter(saved: SavedFilter) = update { d ->
        d.copy(savedFilters = d.savedFilters.filterNot { it.name == saved.name } + saved)
    }

    suspend fun removeFilter(name: String) = update { d ->
        d.copy(savedFilters = d.savedFilters.filterNot { it.name == name })
    }

    suspend fun removeSubscription(id: String) = update { d ->
        d.copy(subscriptions = d.subscriptions.filterNot { it.id == id })
    }
}
