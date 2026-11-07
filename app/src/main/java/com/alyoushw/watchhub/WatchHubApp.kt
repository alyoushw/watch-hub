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
import java.io.File

/** Simple manual dependency container shared by the whole app. */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val store = LocalStore(File(context.filesDir, "watchhub.json"))
    val api = TmdbApi(settings)
    val links = DirectLinks()
    val jikan = JikanApi()
    val assistant = ClaudeAssistant(settings)
    val statusCache = StatusCache(context, api)

    /** The list a "عرض الكل" button asked to open; read by the browse screen. */
    var browse: BrowseRequest? = null
}

data class BrowseRequest(val title: String, val source: RowSource, val ranked: Boolean)

class WatchHubApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
