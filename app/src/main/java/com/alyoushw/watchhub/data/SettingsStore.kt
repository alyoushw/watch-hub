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

    private val _titleHintSeconds = MutableStateFlow(prefs.getInt(KEY_HINT_SECONDS, 10))

    /** How long an extra app shows the title to type, in seconds (TVs have no paste). */
    val titleHintSeconds: StateFlow<Int> = _titleHintSeconds.asStateFlow()

    fun setTitleHintSeconds(value: Int) {
        prefs.edit().putInt(KEY_HINT_SECONDS, value).apply()
        _titleHintSeconds.value = value
    }

    private val _xtream = MutableStateFlow(
        XtreamAccount(
            host = prefs.getString(KEY_XTREAM_HOST, "").orEmpty(),
            username = prefs.getString(KEY_XTREAM_USER, "").orEmpty(),
            password = prefs.getString(KEY_XTREAM_PASS, "").orEmpty(),
        ),
    )

    /** The user's Xtream Codes login (host, username, password) from their official provider. */
    val xtream: StateFlow<XtreamAccount> = _xtream.asStateFlow()

    fun setXtream(account: XtreamAccount) {
        val clean = XtreamAccount(
            host = account.host.trim().takeIf { it.isNotEmpty() }?.let { XtreamApi.normalizeHost(it) }.orEmpty(),
            username = account.username.trim(),
            password = account.password,
        )
        prefs.edit()
            .putString(KEY_XTREAM_HOST, clean.host)
            .putString(KEY_XTREAM_USER, clean.username)
            .putString(KEY_XTREAM_PASS, clean.password)
            .apply()
        _xtream.value = clean
    }

    fun clearXtream() = setXtream(XtreamAccount("", "", ""))

    private val _xtreamFavorites = MutableStateFlow(
        prefs.getString(KEY_XTREAM_FAV, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
    )

    /** Stream ids of the live channels the user starred. */
    val xtreamFavorites: StateFlow<Set<String>> = _xtreamFavorites.asStateFlow()

    fun toggleXtreamFavorite(id: String) {
        val now = _xtreamFavorites.value.let { if (id in it) it - id else it + id }
        prefs.edit().putString(KEY_XTREAM_FAV, now.joinToString(",")).apply()
        _xtreamFavorites.value = now
    }

    private val _xtreamRecent = MutableStateFlow(
        prefs.getString(KEY_XTREAM_RECENT, "").orEmpty().split(',').filter { it.isNotBlank() },
    )

    /** Live channels watched lately, newest first. */
    val xtreamRecent: StateFlow<List<String>> = _xtreamRecent.asStateFlow()

    fun addXtreamRecent(id: String) {
        val now = (listOf(id) + _xtreamRecent.value.filter { it != id }).take(30)
        prefs.edit().putString(KEY_XTREAM_RECENT, now.joinToString(",")).apply()
        _xtreamRecent.value = now
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
        const val KEY_HINT_SECONDS = "title_hint_seconds"
        const val KEY_XTREAM_HOST = "xtream_host"
        const val KEY_XTREAM_USER = "xtream_user"
        const val KEY_XTREAM_PASS = "xtream_pass"
        const val KEY_XTREAM_FAV = "xtream_favorites"
        const val KEY_XTREAM_RECENT = "xtream_recent"
    }
}
