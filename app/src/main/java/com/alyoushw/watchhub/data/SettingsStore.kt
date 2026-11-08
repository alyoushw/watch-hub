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

    private val accountsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val accountsSerializer = kotlinx.serialization.builtins.ListSerializer(XtreamAccount.serializer())

    private fun loadAccounts(): List<XtreamAccount> {
        prefs.getString(KEY_XTREAM_ACCOUNTS, null)?.let { raw ->
            return runCatching { accountsJson.decodeFromString(accountsSerializer, raw) }.getOrDefault(emptyList())
        }
        // The single account of earlier versions becomes the first one in the list.
        val old = XtreamAccount(
            host = prefs.getString(KEY_XTREAM_HOST, "").orEmpty(),
            username = prefs.getString(KEY_XTREAM_USER, "").orEmpty(),
            password = prefs.getString(KEY_XTREAM_PASS, "").orEmpty(),
            id = "a1",
            name = "حسابي",
        )
        return if (old.isComplete) listOf(old) else emptyList()
    }

    private val _xtreamAccounts = MutableStateFlow(loadAccounts())

    /** Every Xtream Codes login the user added, in the order added. */
    val xtreamAccounts: StateFlow<List<XtreamAccount>> = _xtreamAccounts.asStateFlow()

    private fun pickActive(id: String?): XtreamAccount =
        _xtreamAccounts.value.firstOrNull { it.id == id } ?: _xtreamAccounts.value.firstOrNull() ?: XtreamAccount("", "", "")

    private val _xtream = MutableStateFlow(pickActive(prefs.getString(KEY_XTREAM_ACTIVE, null)))

    /** The account the channels, films and series screens are showing. */
    val xtream: StateFlow<XtreamAccount> = _xtream.asStateFlow()

    fun xtreamAccount(id: String?): XtreamAccount? = _xtreamAccounts.value.firstOrNull { it.id == id }

    private fun storeAccounts(list: List<XtreamAccount>) {
        _xtreamAccounts.value = list
        prefs.edit().putString(KEY_XTREAM_ACCOUNTS, accountsJson.encodeToString(accountsSerializer, list)).apply()
        _xtream.value = pickActive(_xtream.value.id)
    }

    /** Adds [account], or replaces the one with the same id. Returns the saved copy. */
    fun saveXtreamAccount(account: XtreamAccount): XtreamAccount {
        val id = account.id.ifBlank { "a" + System.currentTimeMillis().toString(36) }
        val clean = account.copy(
            id = id,
            name = account.name.trim(),
            host = account.host.trim().takeIf { it.isNotEmpty() }?.let { XtreamApi.normalizeHost(it) }.orEmpty(),
            username = account.username.trim(),
        )
        val list = _xtreamAccounts.value
        storeAccounts(if (list.any { it.id == id }) list.map { if (it.id == id) clean else it } else list + clean)
        if (list.isEmpty()) setActiveXtream(id)
        return clean
    }

    fun removeXtreamAccount(id: String) = storeAccounts(_xtreamAccounts.value.filter { it.id != id })

    fun setActiveXtream(id: String) {
        prefs.edit().putString(KEY_XTREAM_ACTIVE, id).apply()
        _xtream.value = pickActive(id)
    }

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

    private val _themeMode = MutableStateFlow(prefs.getString(KEY_THEME, "dark") ?: "dark")

    /** "dark", "light" or "system". */
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    fun setThemeMode(value: String) {
        prefs.edit().putString(KEY_THEME, value).apply()
        _themeMode.value = value
    }

    private val _accent = MutableStateFlow(prefs.getString(KEY_ACCENT, "gold") ?: "gold")

    /** Accent colour key (see ui/Theme.kt Accents). */
    val accent: StateFlow<String> = _accent.asStateFlow()

    fun setAccent(value: String) {
        prefs.edit().putString(KEY_ACCENT, value).apply()
        _accent.value = value
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_ACCENT = "accent"
        const val KEY_TMDB = "tmdb_key"
        const val KEY_REGION = "region"
        const val KEY_APPS = "custom_apps"
        const val KEY_CLAUDE = "claude_key"
        const val KEY_USE_CLAUDE = "use_claude"
        const val KEY_HINT_SECONDS = "title_hint_seconds"
        const val KEY_XTREAM_HOST = "xtream_host"
        const val KEY_XTREAM_USER = "xtream_user"
        const val KEY_XTREAM_PASS = "xtream_pass"
        const val KEY_XTREAM_ACCOUNTS = "xtream_accounts"
        const val KEY_XTREAM_ACTIVE = "xtream_active"
        const val KEY_XTREAM_FAV = "xtream_favorites"
        const val KEY_XTREAM_RECENT = "xtream_recent"
    }
}
