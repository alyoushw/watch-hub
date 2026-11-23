package com.alyoushw.watchhub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _watchNext = MutableStateFlow(prefs.getBoolean("watch_next", false))
    val watchNext: StateFlow<Boolean> = _watchNext.asStateFlow()
    fun setWatchNext(value: Boolean) {
        prefs.edit().putBoolean("watch_next", value).apply()
        _watchNext.value = value
    }

    private val _apiKey = MutableStateFlow(com.alyoushw.watchhub.BuildConfig.TMDB_KEY.trim()
        .ifBlank { prefs.getString(KEY_TMDB, "").orEmpty() })
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

    /**
     * Old account ids (random, per device) → stable ids, from moving to [XtreamAccount.stableId].
     * The watch history uses it once to re-point its entries (see AppContainer).
     */
    val accountIdChanges: Map<String, String>

    private val _xtreamAccounts: MutableStateFlow<List<XtreamAccount>>

    @kotlinx.serialization.Serializable
    private data class SeedAccount(val name: String = "", val host: String = "", val user: String = "", val pass: String = "")

    /** Accounts baked into the build (WATCHHUB_DEFAULT_ACCOUNTS); added once on the very first launch. */
    private fun defaultSeedAccounts(): List<XtreamAccount> {
        val raw = com.alyoushw.watchhub.BuildConfig.DEFAULT_ACCOUNTS.trim()
        if (raw.isBlank()) return emptyList()
        val seeds = runCatching {
            accountsJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SeedAccount.serializer()), raw)
        }.getOrDefault(emptyList())
        return seeds.mapNotNull { s ->
            val host = s.host.trim()
            val user = s.user.trim()
            if (host.isBlank() || user.isBlank()) return@mapNotNull null
            val h = XtreamApi.normalizeHost(host)
            XtreamAccount(h, user, s.pass, XtreamAccount.stableId(h, user), s.name.trim())
        }.distinctBy { it.id }
    }

    init {
        val loaded = loadAccounts()
        val changes = mutableMapOf<String, String>()
        var fixed = loaded.map { a ->
            val stable = XtreamAccount.stableId(a.host, a.username)
            if (a.id != stable) changes[a.id] = stable
            a.copy(id = stable)
        }.distinctBy { it.id }
        accountIdChanges = changes
        // First launch only: if the user has no accounts, add the ones baked into the build.
        var seededNow = false
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            if (fixed.isEmpty()) {
                val seed = defaultSeedAccounts()
                if (seed.isNotEmpty()) { fixed = seed; seededNow = true }
            }
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }
        _xtreamAccounts = MutableStateFlow(fixed)
        if (seededNow || changes.isNotEmpty() || prefs.getString(KEY_XTREAM_ACCOUNTS, null) == null && fixed.isNotEmpty()) {
            prefs.edit().putString(KEY_XTREAM_ACCOUNTS, accountsJson.encodeToString(accountsSerializer, fixed)).apply()
            prefs.getString(KEY_XTREAM_ACTIVE, null)?.let { old -> changes[old]?.let { prefs.edit().putString(KEY_XTREAM_ACTIVE, it).apply() } }
        }
    }

    /** Every Xtream Codes login the user added, in the order added. */
    val xtreamAccounts: StateFlow<List<XtreamAccount>> = _xtreamAccounts.asStateFlow()

    private fun pickActive(id: String?): XtreamAccount =
        _xtreamAccounts.value.firstOrNull { it.id == id } ?: _xtreamAccounts.value.firstOrNull() ?: XtreamAccount("", "", "")

    private val _xtream = MutableStateFlow(pickActive(prefs.getString(KEY_XTREAM_ACTIVE, null)))

    /** The account the channels, films and series screens are showing. */
    val xtream: StateFlow<XtreamAccount> = _xtream.asStateFlow()

    fun xtreamAccount(id: String?): XtreamAccount? = _xtreamAccounts.value.firstOrNull { it.id == id }

    /** When the account list last changed (here or, through sync, on another device). */
    val xtreamAccountsUpdatedAt: Long get() = prefs.getLong(KEY_XTREAM_UPDATED, 0)

    /** Called after the user changes the list here (the account sync uploads it). */
    var onXtreamAccountsChanged: ((List<XtreamAccount>, Long) -> Unit)? = null

    private fun storeAccounts(list: List<XtreamAccount>, updatedAt: Long = System.currentTimeMillis(), fromCloud: Boolean = false) {
        _xtreamAccounts.value = list
        prefs.edit()
            .putString(KEY_XTREAM_ACCOUNTS, accountsJson.encodeToString(accountsSerializer, list))
            .putLong(KEY_XTREAM_UPDATED, updatedAt)
            .apply()
        _xtream.value = pickActive(_xtream.value.id)
        if (!fromCloud) onXtreamAccountsChanged?.invoke(list, updatedAt)
    }

    /** The list as synced from another device. */
    fun setXtreamAccountsFromCloud(list: List<XtreamAccount>, updatedAt: Long) =
        storeAccounts(list.map { it.copy(id = XtreamAccount.stableId(it.host, it.username)) }.distinctBy { it.id }, updatedAt, fromCloud = true)

    /** Adds [account], or replaces the one with the same id. Returns the saved copy. */
    fun saveXtreamAccount(account: XtreamAccount): XtreamAccount {
        val host = account.host.trim().takeIf { it.isNotEmpty() }?.let { XtreamApi.normalizeHost(it) }.orEmpty()
        val user = account.username.trim()
        val id = XtreamAccount.stableId(host, user)
        val clean = account.copy(id = id, name = account.name.trim(), host = host, username = user)
        val list = _xtreamAccounts.value
        // Editing may change the login (and so the id): the old entry is replaced in place.
        val replaced = account.id.isNotBlank() && list.any { it.id == account.id }
        storeAccounts(
            if (replaced) list.map { if (it.id == account.id) clean else it }.distinctBy { it.id }
            else list.filter { it.id != id } + clean,
        )
        if (list.isEmpty() || _xtream.value.id == account.id) setActiveXtream(id)
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

    // ---------- YouTube (this device) ----------

    private val _youtube = MutableStateFlow(prefs.getBoolean(KEY_YOUTUBE, false))

    /** Show the "يوتيوب" tab: YouTube's own interface with Watch Hub's picture tools. */
    val youtubeEnabled: StateFlow<Boolean> = _youtube.asStateFlow()

    fun setYoutubeEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_YOUTUBE, on).apply()
        _youtube.value = on
    }

    private val _subscriptionsTab = MutableStateFlow(prefs.getBoolean("subscriptions_tab", false))

    /** Show the «اشتراكاتي» tab (off by default). */
    val subscriptionsTab: StateFlow<Boolean> = _subscriptionsTab.asStateFlow()

    fun setSubscriptionsTab(on: Boolean) {
        prefs.edit().putBoolean("subscriptions_tab", on).apply()
        _subscriptionsTab.value = on
    }

    // ---------- live screen look: folder navigator vs the classic list ----------

    private val _liveFolderUi = MutableStateFlow(prefs.getBoolean(KEY_LIVE_FOLDER_UI, false))

    /** When on, the live tab opens the folder navigator (قسم ▸ مجموعة ▸ قناة ▸ جودة ▸ سيرفر). */
    val liveFolderUi: StateFlow<Boolean> = _liveFolderUi.asStateFlow()

    fun setLiveFolderUi(on: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_FOLDER_UI, on).apply()
        _liveFolderUi.value = on
    }

    private val _folderFavorites = MutableStateFlow(
        prefs.getString(KEY_FOLDER_FAV, "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet(),
    )

    /** Channel keys (channelKey) the user starred in the folder live view; they show at the top. */
    val folderFavorites: StateFlow<Set<String>> = _folderFavorites.asStateFlow()

    fun toggleFolderFavorite(key: String) {
        if (key.isBlank()) return
        val now = _folderFavorites.value.let { if (key in it) it - key else it + key }
        prefs.edit().putString(KEY_FOLDER_FAV, now.joinToString("\n")).apply()
        _folderFavorites.value = now
    }

    // ---------- channel folders ("رياضة", "أخبار", …) ----------

    private val foldersSerializer = kotlinx.serialization.serializer<Map<String, List<String>>>()

    private fun loadFolders(): Map<String, List<String>> = prefs.getString(KEY_XTREAM_FOLDERS, null)
        ?.let { raw -> runCatching { accountsJson.decodeFromString(foldersSerializer, raw) }.getOrNull() }
        .orEmpty()

    private val _xtreamFolders = MutableStateFlow(loadFolders())

    /** The user's channel folders: name → stream ids, in the order they were made. */
    val xtreamFolders: StateFlow<Map<String, List<String>>> = _xtreamFolders.asStateFlow()

    private fun storeFolders(map: Map<String, List<String>>) {
        prefs.edit().putString(KEY_XTREAM_FOLDERS, accountsJson.encodeToString(foldersSerializer, map)).apply()
        _xtreamFolders.value = map
    }

    fun createFolder(name: String) {
        val n = name.trim().take(30)
        if (n.isEmpty() || n in _xtreamFolders.value) return
        storeFolders(_xtreamFolders.value + (n to emptyList()))
    }

    fun deleteFolder(name: String) = storeFolders(_xtreamFolders.value - name)

    fun renameFolder(from: String, to: String) {
        val n = to.trim().take(30)
        if (n.isEmpty() || n == from || n in _xtreamFolders.value) return
        storeFolders(_xtreamFolders.value.entries.associate { (k, v) -> (if (k == from) n else k) to v })
    }

    /** Puts channel [id] in [folder] (made if new), or takes it out. */
    fun setInFolder(folder: String, id: String, inside: Boolean) {
        val map = _xtreamFolders.value.toMutableMap()
        val ids = map[folder].orEmpty()
        map[folder] = if (inside) (ids - id) + id else ids - id
        storeFolders(map)
    }

    // ---------- profiles (guest / signed-in account) ----------

    /** The settings that belong to a person (not to the device): kept apart for the guest and each account. */
    private val profileStringKeys = listOf(KEY_XTREAM_ACCOUNTS, KEY_XTREAM_ACTIVE, KEY_XTREAM_FAV, KEY_XTREAM_RECENT, KEY_XTREAM_FOLDERS, KEY_FOLDER_FAV)

    /**
     * Puts [from]'s personal settings aside (or drops them when [discard]) and brings back [to]'s
     * (empty if it never had any). The device's own settings (TMDB key, region, look) stay.
     */
    fun switchProfile(from: String, to: String, discard: Boolean) {
        val e = prefs.edit()
        for (k in profileStringKeys) {
            if (discard) e.remove("$k@$from") else e.putString("$k@$from", prefs.getString(k, null))
            e.putString(k, prefs.getString("$k@$to", null))
            e.remove("$k@$to")
        }
        if (discard) e.remove("$KEY_XTREAM_UPDATED@$from") else e.putLong("$KEY_XTREAM_UPDATED@$from", prefs.getLong(KEY_XTREAM_UPDATED, 0))
        e.putLong(KEY_XTREAM_UPDATED, prefs.getLong("$KEY_XTREAM_UPDATED@$to", 0))
        e.remove("$KEY_XTREAM_UPDATED@$to")
        // A profile without accounts starts empty (not from the single account of old versions).
        if (prefs.getString("$KEY_XTREAM_ACCOUNTS@$to", null) == null) e.putString(KEY_XTREAM_ACCOUNTS, "[]")
        e.remove(KEY_XTREAM_HOST).remove(KEY_XTREAM_USER).remove(KEY_XTREAM_PASS)
        e.commit()
        reloadProfile()
    }

    /** Forgets this profile's Xtream accounts, favourite and recent channels. */
    fun clearProfile() {
        prefs.edit().putString(KEY_XTREAM_ACCOUNTS, "[]").remove(KEY_XTREAM_ACTIVE).remove(KEY_XTREAM_FAV)
            .remove(KEY_XTREAM_RECENT).remove(KEY_XTREAM_FOLDERS).remove(KEY_FOLDER_FAV).putLong(KEY_XTREAM_UPDATED, 0).commit()
        reloadProfile()
    }

    private fun reloadProfile() {
        _xtreamAccounts.value = loadAccounts()
        _xtream.value = pickActive(prefs.getString(KEY_XTREAM_ACTIVE, null))
        _xtreamFavorites.value = prefs.getString(KEY_XTREAM_FAV, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        _xtreamRecent.value = prefs.getString(KEY_XTREAM_RECENT, "").orEmpty().split(',').filter { it.isNotBlank() }
        _xtreamFolders.value = loadFolders()
        _folderFavorites.value = prefs.getString(KEY_FOLDER_FAV, "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
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

    private val _webUrl = MutableStateFlow(prefs.getString(KEY_WEB_URL, null) ?: DEFAULT_WEB_URL)

    /** The user's Watch Hub web app (Render): where the account settings and the TV code sign-in live. */
    val webUrl: StateFlow<String> = _webUrl.asStateFlow()

    fun setWebUrl(value: String) {
        val v = value.trim().trimEnd('/').let { if (it.isNotEmpty() && !it.startsWith("http")) "https://$it" else it }
        prefs.edit().putString(KEY_WEB_URL, v).apply()
        _webUrl.value = v.ifEmpty { DEFAULT_WEB_URL }
    }

    private val _torrentIndexEnabled = MutableStateFlow(prefs.getBoolean("torrent_index_enabled", true))
    val torrentIndexEnabled: StateFlow<Boolean> = _torrentIndexEnabled.asStateFlow()
    fun setTorrentIndexEnabled(value: Boolean) {
        prefs.edit().putBoolean("torrent_index_enabled", value).apply()
        _torrentIndexEnabled.value = value
    }

    /** The latest searches, newest first. */
    private val _recentSearches = MutableStateFlow(
        prefs.getString("recent_searches", "").orEmpty().split('\u0001').filter { it.isNotBlank() },
    )
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()
    fun addRecentSearch(value: String) {
        val q = value.trim().replace('\u0001', ' ')
        if (q.length < 2) return
        saveRecentSearches(listOf(q) + _recentSearches.value.filterNot { it.equals(q, ignoreCase = true) })
    }
    fun removeRecentSearch(value: String) = saveRecentSearches(_recentSearches.value - value)
    fun clearRecentSearches() = saveRecentSearches(emptyList())
    private fun saveRecentSearches(list: List<String>) {
        val kept = list.take(15)
        prefs.edit().putString("recent_searches", kept.joinToString("\u0001")).apply()
        _recentSearches.value = kept
    }

    /** The «كوره» site; empty means the built-in address. */
    private val _koraUrl = MutableStateFlow(prefs.getString("kora_url", "").orEmpty())
    val koraUrl: StateFlow<String> = _koraUrl.asStateFlow()
    val koraUrlUpdatedAt: Long get() = prefs.getLong("kora_url_at", 0)
    /** The account sync: the «كوره» address, and its saved login (cookies), follow the Google account. */
    var onKoraChanged: (() -> Unit)? = null
    fun setKoraUrl(value: String) {
        var url = value.trim()
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        prefs.edit().putString("kora_url", url).putLong("kora_url_at", System.currentTimeMillis()).apply()
        _koraUrl.value = url
        onKoraChanged?.invoke()
    }
    fun setKoraUrlFromCloud(url: String, at: Long) {
        if (at <= koraUrlUpdatedAt) return
        prefs.edit().putString("kora_url", url).putLong("kora_url_at", at).apply()
        _koraUrl.value = url
    }

    // The «كوره» site login, kept as its cookies so a sign-in on one device reaches the others.
    private val _koraCookies = MutableStateFlow(prefs.getString("kora_cookies", "").orEmpty())
    val koraCookies: StateFlow<String> = _koraCookies.asStateFlow()
    val koraCookiesUpdatedAt: Long get() = prefs.getLong("kora_cookies_at", 0)
    fun setKoraCookies(value: String) {
        if (value.isBlank() || value == _koraCookies.value) return
        prefs.edit().putString("kora_cookies", value).putLong("kora_cookies_at", System.currentTimeMillis()).apply()
        _koraCookies.value = value
        onKoraChanged?.invoke()
    }
    fun setKoraCookiesFromCloud(value: String, at: Long) {
        if (at <= koraCookiesUpdatedAt || value.isBlank()) return
        prefs.edit().putString("kora_cookies", value).putLong("kora_cookies_at", at).apply()
        _koraCookies.value = value
    }

    private val _sourceCatalogue = MutableStateFlow(prefs.getString("source_catalogue", "").orEmpty())
    val sourceCatalogue: StateFlow<String> = _sourceCatalogue.asStateFlow()
    fun setSourceCatalogue(value: String) {
        val url = value.trim()
        prefs.edit().putString("source_catalogue", url).apply()
        _sourceCatalogue.value = url
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
        const val KEY_WEB_URL = "web_url"
        const val KEY_XTREAM_UPDATED = "xtream_accounts_updated"
        const val DEFAULT_WEB_URL = "https://watch-hub-ddfg.onrender.com"
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
        const val KEY_XTREAM_FOLDERS = "xtream_folders"
        const val KEY_YOUTUBE = "youtube_enabled"
        const val KEY_SEEDED = "accounts_seeded"
        const val KEY_LIVE_FOLDER_UI = "live_folder_ui"
        const val KEY_FOLDER_FAV = "folder_favorites"
    }
}
