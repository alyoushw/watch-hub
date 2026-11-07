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
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(intent, 0)
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
 * Opens the app searching for [title] when it supports Android's standard search intent;
 * otherwise copies the title (to paste into the app's search) and opens the app.
 */
@Suppress("DEPRECATION")
fun Context.openCustomApp(packageName: String, title: String) {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    if (launch == null) {
        Toast.makeText(this, "التطبيق مو مثبت على جوالك", Toast.LENGTH_SHORT).show()
        return
    }

    // The title is always copied too, in case the app opens without filling its search box.
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("title", title))

    val search = Intent(Intent.ACTION_SEARCH)
        .setPackage(packageName)
        .putExtra(SearchManager.QUERY, title)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (packageManager.queryIntentActivities(search, 0).isNotEmpty()) {
        try {
            startActivity(search)
            Toast.makeText(this, "نبحث عن «$title»", Toast.LENGTH_SHORT).show()
            return
        } catch (_: Exception) {
            // Fall through to opening the app normally.
        }
    }

    Toast.makeText(this, "التطبيق ما يدعم البحث المباشر. نسخت «$title»، الصقه في البحث", Toast.LENGTH_LONG).show()
    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Whether the app accepts Android's standard search intent (so it can open on its results). */
@Suppress("DEPRECATION")
fun Context.supportsDirectSearch(packageName: String): Boolean {
    val search = Intent(Intent.ACTION_SEARCH).setPackage(packageName)
    return packageManager.queryIntentActivities(search, 0).isNotEmpty()
}
