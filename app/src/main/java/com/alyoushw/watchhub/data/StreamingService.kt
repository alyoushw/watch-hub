package com.alyoushw.watchhub.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

/** The streaming services the app knows how to open directly. */
enum class StreamingService(
    val key: String,
    val label: String,
    /** TMDB/JustWatch provider ids that belong to this service. */
    val tmdbIds: Set<Int>,
    val packageName: String,
    val homeUrl: String,
    private val searchUrlPrefix: String,
    val color: Long,
) {
    NETFLIX(
        key = "netflix",
        label = "نتفلكس",
        tmdbIds = setOf(8, 1796),
        packageName = "com.netflix.mediaclient",
        homeUrl = "https://www.netflix.com/",
        searchUrlPrefix = "https://www.netflix.com/search?q=",
        color = 0xFFE50914,
    ),
    PRIME(
        key = "prime",
        label = "برايم فيديو",
        tmdbIds = setOf(9, 119, 2100),
        packageName = "com.amazon.avod.thirdpartyclient",
        homeUrl = "https://www.primevideo.com/",
        searchUrlPrefix = "https://www.primevideo.com/search/?phrase=",
        color = 0xFF00A8E1,
    ),
    CRUNCHYROLL(
        key = "crunchyroll",
        label = "كرنشي رول",
        tmdbIds = setOf(283, 1968),
        packageName = "com.crunchyroll.crunchyroid",
        homeUrl = "https://www.crunchyroll.com/",
        searchUrlPrefix = "https://www.crunchyroll.com/search?q=",
        color = 0xFFF47521,
    );

    fun searchUrl(title: String): String = searchUrlPrefix + URLEncoder.encode(title, "UTF-8")

    companion object {
        fun forProvider(providerId: Int): StreamingService? = entries.firstOrNull { providerId in it.tmdbIds }
        fun forKey(key: String?): StreamingService? = entries.firstOrNull { it.key == key }

        fun forUrl(url: String): StreamingService? {
            val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return null
            return when {
                "netflix." in host -> NETFLIX
                "crunchyroll." in host -> CRUNCHYROLL
                "primevideo." in host || "amazon." in host -> PRIME
                else -> null
            }
        }
    }
}

/**
 * Starting another app from our screen keeps us underneath it, so Back (or closing it)
 * returns to the exact page the user was on. From a non-screen context a new task is required.
 */
fun Intent.launchedFrom(context: Context): Intent =
    if (context is android.app.Activity) this else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** The app's start screen: its phone launcher entry, or its TV (leanback) one on TVs. */
fun Context.launchIntentFor(packageName: String): Intent? =
    packageManager.getLaunchIntentForPackage(packageName)
        ?: packageManager.getLeanbackLaunchIntentForPackage(packageName)

fun Context.isInstalled(packageName: String): Boolean = launchIntentFor(packageName) != null

val Context.isTvDevice: Boolean
    get() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).launchedFrom(this))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "ما فيه تطبيق يفتح هذا الرابط", Toast.LENGTH_SHORT).show()
    }
}

/** Every package the service's app ships under (phone and Fire/TV builds). */
private val StreamingService.packages: List<String>
    get() = when (this) {
        StreamingService.NETFLIX -> listOf(packageName, "com.netflix.ninja")
        StreamingService.PRIME -> listOf(packageName, "com.amazon.avod", "com.amazon.amazonvideo.livingroom")
        StreamingService.CRUNCHYROLL -> listOf(packageName)
    }

/**
 * The installed copy of the service's app, if any. Known package names first; otherwise any
 * launchable app whose name matches the service (TV builds sometimes use other packages).
 */
fun Context.installedPackage(service: StreamingService): String? =
    service.packages.firstOrNull { isInstalled(it) } ?: findByLabel(service)

@Suppress("DEPRECATION")
private fun Context.findByLabel(service: StreamingService): String? {
    val names = when (service) {
        StreamingService.NETFLIX -> listOf("netflix", "نتفليكس", "نتفلكس")
        StreamingService.PRIME -> listOf("prime video", "primevideo", "برايم")
        StreamingService.CRUNCHYROLL -> listOf("crunchyroll", "كرانشي", "كرنشي")
    }
    val pm = packageManager
    for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        for (info in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
            val label = info.loadLabel(pm).toString().lowercase()
            if (names.any { it in label }) return info.activityInfo.packageName
        }
    }
    return null
}

/**
 * Rewrites a link into the form the service's app accepts.
 * Wikidata often gives Prime titles as amazon.com/gp/video/detail/ID (the US store), which the
 * Prime Video app ignores; the same ID works as primevideo.com/detail/ID.
 */
fun appLinkFor(service: StreamingService, url: String): String {
    if (service == StreamingService.PRIME) {
        val id = Regex("/(?:gp/video/detail|dp|detail)/([A-Za-z0-9.]+)").find(url)?.groupValues?.get(1)
        if (id != null) return "https://www.primevideo.com/detail/$id"
    }
    if (service == StreamingService.CRUNCHYROLL) {
        // Drop a language prefix (crunchyroll.com/ar/series/...) and force https://www.
        return url
            .replace(Regex("^https?://(www\\.)?crunchyroll\\.com/"), "https://www.crunchyroll.com/")
            .replace(Regex("crunchyroll\\.com/[a-z]{2}(-[a-z]{2})?/(?=(series|watch)/)"), "crunchyroll.com/")
    }
    return url
}

/**
 * Old-style Crunchyroll links (crunchyroll.com/anime-name, from before 2022) don't open in the app;
 * for those the in-app search is the better target.
 */
private fun isLegacyCrunchyrollLink(url: String): Boolean =
    "crunchyroll.com" in url && !Regex("crunchyroll\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:series|watch)/").containsMatchIn(url)

private fun Context.tryView(url: String, pkg: String): Boolean = try {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).launchedFrom(this)
    // Netflix only follows a link to the title itself (not just its home screen) when told where it came from.
    if ("netflix" in pkg) intent.putExtra("source", "30")
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * Opens [url] in the service's app. When the app is installed but won't take the link,
 * it still opens the app (on its search screen if it has one) with [title] copied, rather
 * than sending the user to the website. Only without the app does the browser open.
 */
fun Context.openInService(service: StreamingService, url: String, title: String) {
    val pkg = installedPackage(service)
    if (pkg == null) {
        if (isTvDevice) {
            // TVs have no useful browser for these sites; say so instead of a confusing app chooser.
            Toast.makeText(this, "تطبيق ${service.label} مو مثبت على التلفاز", Toast.LENGTH_LONG).show()
        } else {
            openUrl(url)
        }
        return
    }
    if (service == StreamingService.CRUNCHYROLL && isLegacyCrunchyrollLink(url) && "/search" !in url) {
        // Straight to the app's search for links the app can't open.
        if (tryView(service.searchUrl(title), pkg)) return
        openCustomApp(pkg, title)
        return
    }
    val appUrl = appLinkFor(service, url)
    if (service == StreamingService.CRUNCHYROLL) {
        // The Crunchyroll app (especially on TVs) may ignore web links but accept its own scheme.
        val m = Regex("crunchyroll\\.com/(series|watch)/([A-Za-z0-9]+)").find(appUrl)
        if (m != null && tryView("crunchyroll://${m.groupValues[1]}/${m.groupValues[2]}", pkg)) return
    }
    if (tryView(appUrl, pkg)) return
    if (appUrl != url && tryView(url, pkg)) return
    // The app doesn't accept web links: open it searching for the title, or just open it.
    openCustomApp(pkg, title)
}

/** Opens a search for [title] inside the service's app, or its website if the app is missing. */
fun Context.searchOn(service: StreamingService, title: String) = openInService(service, service.searchUrl(title), title)

/**
 * Opens the title itself when a direct link is known, otherwise a search for it.
 * Returns true when a direct link was used.
 */
fun Context.watchOn(service: StreamingService, directUrl: String?, title: String): Boolean {
    return if (directUrl != null) {
        openInService(service, directUrl, title)
        true
    } else {
        searchOn(service, title)
        false
    }
}

/** Launches the service's app, or its website if the app is missing. */
fun Context.launchService(service: StreamingService) {
    val launch = installedPackage(service)?.let { launchIntentFor(it) }
    if (launch != null) {
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } else {
        openUrl(service.homeUrl)
    }
}
