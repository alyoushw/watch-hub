package com.alyoushw.watchhub.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

/** Copies [title] so it can be pasted into the app's search, then opens the app. */
fun Context.openCustomApp(packageName: String, title: String) {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    if (launch == null) {
        Toast.makeText(this, "التطبيق مو مثبت على جوالك", Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("title", title))
    Toast.makeText(this, "نسخت «$title»، الصقه في البحث", Toast.LENGTH_SHORT).show()
    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
