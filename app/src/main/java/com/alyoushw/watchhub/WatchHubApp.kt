package com.alyoushw.watchhub

import android.app.Application
import android.content.Context
import com.alyoushw.watchhub.data.ClaudeAssistant
import com.alyoushw.watchhub.data.DirectLinks
import com.alyoushw.watchhub.data.JikanApi
import com.alyoushw.watchhub.data.LocalStore
import com.alyoushw.watchhub.data.RowSource
import com.alyoushw.watchhub.data.SettingsStore
import com.alyoushw.watchhub.data.StatusCache
import com.alyoushw.watchhub.data.TmdbApi
import com.alyoushw.watchhub.data.XtreamApi
import com.alyoushw.watchhub.data.MediaType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Simple manual dependency container shared by the whole app. */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val store = LocalStore(File(context.filesDir, "watchhub.json"))
    val api = TmdbApi(settings)
    val links = DirectLinks()
    val jikan = JikanApi()
    val assistant = ClaudeAssistant(settings)
    val xtream = XtreamApi(settings)
    val statusCache = StatusCache(context, api)

    private val navPrefs = context.getSharedPreferences("navigation", Context.MODE_PRIVATE)
    // RowSource has its own "type" field, so the subtype marker needs another name ("kind").
    private val navJson = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }

    /**
     * The list a "عرض الكل" button asked to open. Saved on disk so the page is still there
     * if Android closes the app in the background (e.g. while a film plays in Netflix).
     */
    private var memoryBrowse: BrowseRequest? = null

    var browse: BrowseRequest?
        get() = memoryBrowse ?: runCatching {
            navPrefs.getString("browse", null)?.let { navJson.decodeFromString(BrowseRequest.serializer(), it) }
        }.getOrNull()
        set(value) {
            // Saving is only a convenience; it must never take the app down.
            val encoded = runCatching { value?.let { navJson.encodeToString(BrowseRequest.serializer(), it) } }.getOrNull()
            navPrefs.edit().putString("browse", encoded).apply()
            memoryBrowse = value
        }

    /** Last Home tab (series / movies / anime), restored on the next start. */
    var homeType: MediaType
        get() = MediaType.fromPath(navPrefs.getString("home_type", null)) ?: MediaType.TV
        set(value) = navPrefs.edit().putString("home_type", value.path).apply()
}

@Serializable
data class BrowseRequest(val title: String, val source: RowSource, val ranked: Boolean)

class WatchHubApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
