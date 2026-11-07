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
    }
}

fun Context.isInstalled(packageName: String): Boolean =
    packageManager.getLaunchIntentForPackage(packageName) != null

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "ما فيه تطبيق يفتح هذا الرابط", Toast.LENGTH_SHORT).show()
    }
}

/** Opens [url] inside the service's app when it is installed, otherwise in the browser. */
fun Context.openInService(service: StreamingService, url: String) {
    if (isInstalled(service.packageName)) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .setPackage(service.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        } catch (_: ActivityNotFoundException) {
            // The app does not handle this link; fall through to the browser.
        }
    }
    openUrl(url)
}

/** Opens a search for [title] inside the service's app, or its website if the app is missing. */
fun Context.searchOn(service: StreamingService, title: String) = openInService(service, service.searchUrl(title))

/**
 * Opens the title itself when a direct link is known, otherwise a search for it.
 * Returns true when the direct page was opened.
 */
fun Context.watchOn(service: StreamingService, directUrl: String?, title: String): Boolean {
    return if (directUrl != null) {
        openInService(service, directUrl)
        true
    } else {
        searchOn(service, title)
        false
    }
}

/** Launches the service's app, or its website if the app is missing. */
fun Context.launchService(service: StreamingService) {
    val launch = packageManager.getLaunchIntentForPackage(service.packageName)
    if (launch != null) {
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } else {
        openUrl(service.homeUrl)
    }
}
