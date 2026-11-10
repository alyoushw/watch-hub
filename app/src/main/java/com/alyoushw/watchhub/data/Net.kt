package com.alyoushw.watchhub.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One connection pool for the Xtream account (its lists and its streams), dropped whenever the
 * phone moves to another network (Wi-Fi ↔ mobile data). Old connections made on the network
 * that is gone would otherwise hang until they time out, and the stream would not come back.
 */
object Net {
    val streams: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _changes = MutableStateFlow(0)

    /** Goes up by one each time the network changes (the player reconnects on it). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private var current: Network? = null

    fun watch(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val before = current
                    current = network
                    if (before != null && before != network) changed()
                }

                override fun onLost(network: Network) {
                    if (network == current) streams.connectionPool.evictAll()
                }
            })
        }
    }

    private fun changed() {
        streams.connectionPool.evictAll()
        _changes.value++
    }
}
