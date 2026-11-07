package com.alyoushw.watchhub

import android.app.Application
import android.content.Context
import com.alyoushw.watchhub.data.LocalStore
import com.alyoushw.watchhub.data.SettingsStore
import com.alyoushw.watchhub.data.TmdbApi
import java.io.File

/** Simple manual dependency container shared by the whole app. */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val store = LocalStore(File(context.filesDir, "watchhub.json"))
    val api = TmdbApi(settings)
}

class WatchHubApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
