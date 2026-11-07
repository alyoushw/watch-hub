package com.alyoushw.watchhub.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.SearchManager
import android.content.Intent
import android.widget.Toast

/** An app the user can pin as an extra "open in" shortcut. */
data class InstalledApp(val packageName: String, val label: String)

/** Every app with a launcher icon, except this one, sorted by name. */
@Suppress("DEPRECATION")
fun Context.launchableApps(): List<InstalledApp> {
    // Phone apps and TV (leanback) apps.
    val infos = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER).flatMap { category ->
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
    }
    return infos
        .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
        .filter { it.packageName != packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

fun Context.appLabel(packageName: String): String? = runCatching {
    val info = packageManager.getApplicationInfo(packageName, 0)
    packageManager.getApplicationLabel(info).toString()
}.getOrNull()

/**
 * Opens the app looking for [title], trying in order:
 * 1. Android's standard search intent (the app opens on its results);
 * 2. sharing the title as text to the app (some apps search what they receive);
 * 3. opening the app with the title copied. TVs have no paste button, so the title is also
 *    shown in a long message to type or say with the remote's mic.
 */
@Suppress("DEPRECATION")
fun Context.openCustomApp(packageName: String, title: String) {
    val launch = launchIntentFor(packageName)
    if (launch == null) {
        Toast.makeText(this, "التطبيق مو مثبت على الجهاز", Toast.LENGTH_SHORT).show()
        return
    }

    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("title", title))

    val search = Intent(Intent.ACTION_SEARCH)
        .setPackage(packageName)
        .putExtra(SearchManager.QUERY, title)
        .launchedFrom(this)
    if (packageManager.queryIntentActivities(search, 0).isNotEmpty() && tryStart(search)) {
        Toast.makeText(this, "نبحث عن «$title»", Toast.LENGTH_SHORT).show()
        return
    }

    val share = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .setPackage(packageName)
        .putExtra(Intent.EXTRA_TEXT, title)
        .launchedFrom(this)
    if (packageManager.queryIntentActivities(share, 0).isNotEmpty() && tryStart(share)) return

    val hint = if (isTvDevice) "ابحث عن: «$title»\n(اكتبه أو قوله بمايك الريموت)" else "نسخت «$title»، الصقه في البحث"
    Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    if (isTvDevice) {
        // A second long message so the title stays on screen while the app opens.
        android.os.Handler(mainLooper).postDelayed({ Toast.makeText(this, hint, Toast.LENGTH_LONG).show() }, 3500)
    }
}

private fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: Exception) {
    false
}

/** Whether the app accepts Android's standard search intent (so it can open on its results). */
@Suppress("DEPRECATION")
fun Context.supportsDirectSearch(packageName: String): Boolean {
    val search = Intent(Intent.ACTION_SEARCH).setPackage(packageName)
    return packageManager.queryIntentActivities(search, 0).isNotEmpty()
}
