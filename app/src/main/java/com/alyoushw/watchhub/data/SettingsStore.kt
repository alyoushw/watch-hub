package com.alyoushw.watchhub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_TMDB, "").orEmpty())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _region = MutableStateFlow(prefs.getString(KEY_REGION, "SA") ?: "SA")
    val region: StateFlow<String> = _region.asStateFlow()

    fun setApiKey(value: String) {
        val v = value.trim()
        prefs.edit().putString(KEY_TMDB, v).apply()
        _apiKey.value = v
    }

    fun setRegion(value: String) {
        prefs.edit().putString(KEY_REGION, value).apply()
        _region.value = value
    }

    private companion object {
        const val KEY_TMDB = "tmdb_key"
        const val KEY_REGION = "region"
    }
}
