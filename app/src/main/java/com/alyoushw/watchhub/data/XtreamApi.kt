package com.alyoushw.watchhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The user's own Xtream Codes login from their provider. */
data class XtreamAccount(val host: String, val username: String, val password: String) {
    val isComplete: Boolean get() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/** What the provider says about the account (from player_api.php's user_info). */
data class XtreamAccountInfo(
    val status: String?,
    val expiresAt: Date?,
    val maxConnections: String?,
    val activeConnections: String?,
)

class XtreamException(message: String) : ApiException(message)

/** Minimal client for the Xtream Codes player API, used to check the saved login. */
class XtreamApi {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Logs in with [account] and returns the account details, or throws [XtreamException]. */
    suspend fun check(account: XtreamAccount): XtreamAccountInfo = withContext(Dispatchers.IO) {
        val base = normalizeHost(account.host).toHttpUrlOrNull()
            ?: throw XtreamException("رابط الهوست غير صحيح، مثال: http://example.com:8080")
        val url = base.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
            .build()

        val body = try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code == 401 || response.code == 403) throw XtreamException("اسم المستخدم أو كلمة المرور غلط")
                if (!response.isSuccessful) throw XtreamException("السيرفر رد بخطأ (${response.code})")
                response.body?.string().orEmpty()
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: IOException) {
            throw XtreamException("ما قدرنا نوصل للسيرفر، تأكد من الهوست والإنترنت")
        }

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw XtreamException("الرد مو من سيرفر Xtream Codes، تأكد من الهوست")
        val user = root["user_info"] as? JsonObject
            ?: throw XtreamException("الرد مو من سيرفر Xtream Codes، تأكد من الهوست")

        if (user.text("auth") != "1") throw XtreamException("اسم المستخدم أو كلمة المرور غلط")

        XtreamAccountInfo(
            status = user.text("status"),
            expiresAt = user.text("exp_date")?.toLongOrNull()?.let { Date(it * 1000) },
            maxConnections = user.text("max_connections"),
            activeConnections = user.text("active_cons"),
        )
    }

    private fun JsonObject.text(key: String): String? {
        val value: JsonElement = this[key] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** "example.com:8080/" → "http://example.com:8080", and drops a pasted "player_api.php". */
        fun normalizeHost(raw: String): String {
            var host = raw.trim().trimEnd('/')
            if (host.endsWith("player_api.php")) host = host.removeSuffix("player_api.php").trimEnd('/')
            if (!host.startsWith("http://", ignoreCase = true) && !host.startsWith("https://", ignoreCase = true)) {
                host = "http://$host"
            }
            return host
        }

        fun formatDate(date: Date): String = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(date)
    }
}
