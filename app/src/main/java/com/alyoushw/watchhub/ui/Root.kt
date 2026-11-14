package com.alyoushw.watchhub.ui

import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.BrowseRequest
import com.alyoushw.watchhub.WatchHubApp
import com.alyoushw.watchhub.data.MediaType

@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as WatchHubApp).container

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "الرئيسية", Icons.Filled.Home),
    KORA("kora", "كوره", Icons.Filled.DateRange),
    SEARCH("search", "البحث", Icons.Filled.Search),
    WATCHLIST("watchlist", "قائمتي", Icons.AutoMirrored.Filled.List),
    SUBSCRIPTIONS("subscriptions", "اشتراكاتي", Icons.Filled.DateRange),
    XTREAM("xtream", "قنواتي", Icons.Filled.PlayArrow),
    YOUTUBE("youtube", "يوتيوب", com.alyoushw.watchhub.player.PlayerIcons.YouTube),
    SETTINGS("settings", "الإعدادات", Icons.Filled.Settings),
}

fun NavHostController.openDetail(type: MediaType, id: Int) = navigate("detail/${type.path}/$id")

/**
 * A tab always opens at its own first page: tapping «الرئيسية» while on a title page goes back to
 * Home itself, not to the page left open there before. (Nothing is saved or restored per tab.)
 */
private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id)
    launchSingleTop = true
}

@Composable
fun WatchHubRoot(nav: NavHostController = rememberNavController()) {
    val container = appContainer()
    val watchNextRequest by container.watchNextRequest.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(watchNextRequest) {
        watchNextRequest?.let { (type, id) ->
            nav.openDetail(type, id)
            container.watchNextRequest.value = null
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val tv = isTv()
    // TVs and wide car screens: tabs in a side rail (a bottom bar eats a short screen's height).
    val rail = tv || isWideShort()
    // A TV's "scan to sign in" link opened on this phone: ask to approve it, over any screen.
    PairApprovalDialog()
    CrashNotice()
    UpdateNotice()
    // The Xtream tab only shows once an account is saved in Settings.
    val xtream by appContainer().settings.xtream.collectAsStateWithLifecycle()
    // The YouTube tab once it is switched on (Settings › يوتيوب).
    val youtube by appContainer().settings.youtubeEnabled.collectAsStateWithLifecycle()
    val tabs = Tab.entries.filter { (it != Tab.XTREAM || xtream.isComplete) && (it != Tab.YOUTUBE || youtube) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (!rail) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, maxLines = 1) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        },
    ) { padding ->
      // Landscape phones: keep clear of the side navigation bar and the camera notch.
      Row(
          Modifier.fillMaxSize().padding(padding)
              .windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)),
      ) {
        if (rail) {
            // On TV the tabs sit in a rail on the side, easy to reach with the remote.
            NavigationRail(modifier = Modifier.verticalScroll(rememberScrollState())) {
                tabs.forEach { tab ->
                    NavigationRailItem(
                    modifier = Modifier.focusFrame(),
                        selected = currentRoute == tab.route,
                        onClick = { nav.switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, maxLines = 1) },
                    )
                }
            }
        }
        NavHost(
            navController = nav,
            startDestination = Tab.HOME.route,
            modifier = Modifier.weight(1f).fillMaxSize(),
        ) {
            composable(Tab.HOME.route) {
                val container = appContainer()
                HomeScreen(
                    onOpen = { type, id -> nav.openDetail(type, id) },
                    onOpenSettings = { nav.switchTab(Tab.SETTINGS.route) },
                    onOpenXtreamSeries = { sid -> nav.navigate("xtream/series/${Uri.encode(sid)}") },
                    onOpenAnime = { lookup -> nav.navigate(lookup.route()) },
                    onSeeAll = { section ->
                        val source = section.source
                        if (source != null) {
                            container.browse = BrowseRequest(section.title, source, section.ranked)
                            nav.navigate("browse")
                        }
                    },
                )
            }
            composable(
                route = "person/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
            ) { entry ->
                PersonScreen(
                    id = entry.arguments?.getInt("id") ?: 0,
                    onBack = { nav.popBackStack() },
                    onOpen = { t, i -> nav.openDetail(t, i) },
                )
            }
            composable("browse") {
                BrowseScreen(onBack = { nav.popBackStack() }, onOpen = { type, id -> nav.openDetail(type, id) })
            }
            composable(Tab.SEARCH.route) {
                SearchScreen(
                    onOpen = { type, id -> nav.openDetail(type, id) },
                    onOpenSettings = { nav.switchTab(Tab.SETTINGS.route) },
                    onOpenXtreamSeries = { sid -> nav.navigate("xtream/series/${Uri.encode(sid)}") },
                )
            }
            composable(Tab.WATCHLIST.route) {
                WatchlistScreen(onOpen = { type, id -> nav.openDetail(type, id) })
            }
            composable(Tab.SUBSCRIPTIONS.route) { SubscriptionsScreen() }
            composable(Tab.KORA.route) { KoraScreen(onBack = { nav.switchTab(Tab.HOME.route) }) }
            composable(Tab.YOUTUBE.route) { YouTubeLauncher() }
            composable(Tab.SETTINGS.route) { SettingsScreen(onOpenXtream = { nav.switchTab(Tab.XTREAM.route) }) }
            composable(Tab.XTREAM.route) {
                XtreamScreen(
                    onOpenSeries = { id -> nav.navigate("xtream/series/${Uri.encode(id)}") },
                    onOpenSettings = { nav.switchTab(Tab.SETTINGS.route) },
                )
            }
            composable(
                route = "xtream/series/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                XtreamSeriesScreen(seriesId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("watch-sources/{lookup}", arguments = listOf(navArgument("lookup") { type = NavType.StringType })) { entry ->
                val lookup = runCatching { decodeSourceLookup(entry.arguments?.getString("lookup").orEmpty()) }.getOrNull()
                if (lookup != null) SourceWatchScreen(lookup, onBack = { nav.popBackStack() })
                else MessageBox("تعذّر فتح مصادر المشاهدة", actionLabel = "رجوع", onAction = { nav.popBackStack() })
            }
            composable(
                route = "animeify/{lookup}",
                arguments = listOf(navArgument("lookup") { type = NavType.StringType }),
            ) { entry ->
                val lookup = runCatching { decodeAnimeLookup(entry.arguments?.getString("lookup").orEmpty()) }.getOrNull()
                if (lookup != null) AnimeSourcesScreen(lookup, onBack = { nav.popBackStack() })
                else MessageBox("تعذّر فتح صفحة أنمي فاي", actionLabel = "رجوع", onAction = { nav.popBackStack() })
            }
            composable(
                route = "detail/{type}/{id}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.IntType },
                ),
            ) { entry ->
                val type = MediaType.fromPath(entry.arguments?.getString("type")) ?: MediaType.MOVIE
                val id = entry.arguments?.getInt("id") ?: 0
                if (type == MediaType.ANIME) {
                    AnimeDetailScreen(
                        onOpenAnime = { lookup -> nav.navigate(lookup.route()) },
                        id = id,
                        onBack = { nav.popBackStack() },
                        onOpen = { t, i -> nav.openDetail(t, i) },
                    )
                } else {
                    DetailScreen(
                        onOpenSources = { lookup -> nav.navigate(lookup.route()) },
                        type = type,
                        id = id,
                        onBack = { nav.popBackStack() },
                        onOpen = { t, i -> nav.openDetail(t, i) },
                        onOpenPerson = { personId -> nav.navigate("person/$personId") },
                        onOpenXtreamSeries = { sid -> nav.navigate("xtream/series/${Uri.encode(sid)}") },
                        onOpenAnime = { lookup -> nav.navigate(lookup.route()) },
                    )
                }
            }
        }
      }
    }
}
