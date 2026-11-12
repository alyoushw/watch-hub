package com.alyoushw.watchhub.data

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A newer build on GitHub: its number and where its APK is. */
data class AppUpdate(val build: Int, val url: String, val notes: String?)

/** Keep the existing build-N update protocol and the canonical release asset name. */
internal fun parseAppUpdate(body: String): AppUpdate? = runCatching {
    val o = Json.parseToJsonElement(body).jsonObject
    if (o["draft"]?.jsonPrimitive?.booleanOrNull == true || o["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
    val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
    if (!tag.matches(Regex("build-[1-9][0-9]*"))) return null
    val build = tag.removePrefix("build-").toIntOrNull() ?: return null
    val asset = o["assets"]?.jsonArray?.map { it.jsonObject }
        ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "WatchHub.apk" } ?: return null
    val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.host != "github.com" ||
        url.encodedPath != "/alyoushw/watch-hub/releases/download/$tag/WatchHub.apk") return null
    AppUpdate(build, url.toString(), o["body"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() })
}.getOrNull()

internal fun isNewerBuild(current: Int, candidate: Int): Boolean = current >= 0 && candidate > current
