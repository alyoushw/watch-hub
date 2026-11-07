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

    suspend fun upsertWatch(item: WatchItem) = update { d ->
        val exists = d.watchlist.any { it.key == item.key }
        d.copy(
            watchlist = if (exists) d.watchlist.map { if (it.key == item.key) item else it }
            else d.watchlist + item,
        )
    }

    suspend fun updateWatch(key: String, transform: (WatchItem) -> WatchItem) = update { d ->
        d.copy(watchlist = d.watchlist.map { if (it.key == key) transform(it) else it })
    }

    suspend fun removeWatch(key: String) = update { d ->
        d.copy(watchlist = d.watchlist.filterNot { it.key == key })
    }

    suspend fun upsertSubscription(sub: Subscription) = update { d ->
        val exists = d.subscriptions.any { it.id == sub.id }
        d.copy(
            subscriptions = if (exists) d.subscriptions.map { if (it.id == sub.id) sub else it }
            else d.subscriptions + sub,
        )
    }

    suspend fun removeSubscription(id: String) = update { d ->
        d.copy(subscriptions = d.subscriptions.filterNot { it.id == id })
    }
}
