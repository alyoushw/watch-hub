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

    private val _customApps = MutableStateFlow(
        prefs.getString(KEY_APPS, "").orEmpty().split(',').filter { it.isNotBlank() },
    )

    /** Package names of the user's extra "open in" apps, in the order they were added. */
    val customApps: StateFlow<List<String>> = _customApps.asStateFlow()

    fun setCustomApps(packages: List<String>) {
        val clean = packages.distinct()
        prefs.edit().putString(KEY_APPS, clean.joinToString(",")).apply()
        _customApps.value = clean
    }

    private val _claudeKey = MutableStateFlow(prefs.getString(KEY_CLAUDE, "").orEmpty())

    /** The user's own Claude API key (from the Claude Console), used for smarter search. */
    val claudeKey: StateFlow<String> = _claudeKey.asStateFlow()

    private val _useClaude = MutableStateFlow(prefs.getBoolean(KEY_USE_CLAUDE, true))
    val useClaude: StateFlow<Boolean> = _useClaude.asStateFlow()

    fun setClaudeKey(value: String) {
        val v = value.trim()
        prefs.edit().putString(KEY_CLAUDE, v).apply()
        _claudeKey.value = v
    }

    fun setUseClaude(value: Boolean) {
        prefs.edit().putBoolean(KEY_USE_CLAUDE, value).apply()
        _useClaude.value = value
    }

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
        const val KEY_APPS = "custom_apps"
        const val KEY_CLAUDE = "claude_key"
        const val KEY_USE_CLAUDE = "use_claude"
    }
}
