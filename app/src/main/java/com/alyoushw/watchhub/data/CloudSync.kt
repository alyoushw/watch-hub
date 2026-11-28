package com.alyoushw.watchhub.data

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Public account settings, served by the user's web app at /config.json (from its environment). */
@Serializable
data class CloudConfig(
    val enabled: Boolean = false,
    val firebase: FirebaseConfig? = null,
    val googleWebClientId: String = "",
    val deviceLogin: Boolean = false,
    /** "Scan to sign in" (a signed-in phone approves a TV) is set up on the server. */
    val qrLogin: Boolean = false,
) {
    @Serializable
    data class FirebaseConfig(
        val apiKey: String = "",
        val projectId: String = "",
        val appId: String = "",
        val androidAppId: String = "",
        val authDomain: String = "",
    )

    val usable: Boolean get() = enabled && firebase != null && firebase.apiKey.isNotBlank() && firebase.projectId.isNotBlank()
}

/** "Scan to sign in": the TV shows [url] as a QR code; a signed-in phone opens it and approves. */
data class Pairing(val id: String, val secret: String, val url: String, val expiresIn: Int)

/** A sign-in code to show on a TV: enter [userCode] at [verificationUrl] from a phone or computer. */
data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresIn: Int, val interval: Int)

class CloudException(message: String) : Exception(message)

/**
 * The Watch Hub account: Google sign-in, and keeping "قائمتي", "تابع المشاهدة" (with the exact
 * position) and the Xtream accounts the same on every device signed in to it — phone, TV, web.
 *
 * Data lives in Firestore under users/<uid>/ (only that user can read or write it):
 *   history/<entry key>     one document per channel / film / episode watched
 *   watchlist/<type:id>     one document per title in "قائمتي"
 *   meta/xtream             the list of Xtream accounts
 * Every document carries updatedAt (ms); the newest copy wins. Removals are kept as
 * { deleted: true } so a device that was offline cannot bring them back.
 * The web app (web/public/js/cloud.js) reads and writes exactly the same documents.
 */
class CloudSync(
    private val context: Context,
    private val settings: SettingsStore,
    private val history: WatchHistory,
    private val store: LocalStore,
) {
    sealed interface State {
        /** The web app has no account settings yet (or can't be reached the first time). */
        data object NotConfigured : State
        data object SignedOut : State
        data class SignedIn(val name: String?, val email: String?, val photo: String?) : State
    }

    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    /** «ملخص المشاهدات»: this device's counters go to the account, and the other devices' come back. */
    var stats: WatchStats? = null
        set(value) {
            field = value
            value?.onChange = { scheduleStatsPush() }
        }
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<State>(State.NotConfigured)
    val state: StateFlow<State> = _state.asStateFlow()

    /** When this device last received or sent something (0 = never). */
    private val _lastSync = MutableStateFlow(0L)
    val lastSync: StateFlow<Long> = _lastSync.asStateFlow()

    var config: CloudConfig? = loadCachedConfig()
        private set

    private var auth: FirebaseAuth? = null
    private var db: FirebaseFirestore? = null
    private var uid: String? = null
    private val listeners = mutableListOf<ListenerRegistration>()
    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    // ---------- start-up and configuration ----------

    /** Called once when the app starts: hooks the local stores and connects if possible. */
    fun start() {
        history.onChange = { entry, key, removedAt, important -> pushHistory(entry, key, removedAt, important) }
        history.onHidden = { key, at -> pushHidden(mapOf(key to at)) }
        store.onWatchChanged = { item, key, removedAt -> pushWatch(item, key, removedAt) }
        settings.onXtreamAccountsChanged = { list, at -> pushXtream(list, at) }
        settings.onKoraChanged = { pushKora() }
        settings.onMatchPrefsChanged = { pushMatchPrefs() }
        config?.let { connect(it) }
        // Refresh the settings in the background (they rarely change; the cached copy works offline).
        scope.launch { refreshConfig() }
    }

    private fun loadCachedConfig(): CloudConfig? =
        prefs.getString(KEY_CONFIG, null)?.let { runCatching { json.decodeFromString(CloudConfig.serializer(), it) }.getOrNull() }
            ?.takeIf { it.usable }

    /** Reads /config.json from the web app. Returns true when accounts are available. */
    suspend fun refreshConfig(): Boolean {
        val base = settings.webUrl.value.trimEnd('/')
        val fresh = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("$base/config.json").build()).execute().use { res ->
                    if (!res.isSuccessful) return@use null
                    json.decodeFromString(CloudConfig.serializer(), res.body?.string().orEmpty())
                }
            }.getOrNull()
        } ?: return config != null
        if (!fresh.usable) {
            if (config == null) _state.value = State.NotConfigured
            return config != null
        }
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(CloudConfig.serializer(), fresh)).apply()
        val first = config == null
        config = fresh
        if (first || auth == null) connect(fresh)
        return true
    }

    private fun connect(cfg: CloudConfig) {
        if (auth != null || !cfg.usable) return
        val fb = cfg.firebase ?: return
        try {
            val options = FirebaseOptions.Builder()
                .setApiKey(fb.apiKey)
                .setProjectId(fb.projectId)
                .setApplicationId(fb.androidAppId.ifBlank { fb.appId })
                .build()
            val app = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
                ?: FirebaseApp.initializeApp(context, options, APP_NAME)
            val a = FirebaseAuth.getInstance(app)
            auth = a
            db = FirebaseFirestore.getInstance(app)
            _state.value = State.SignedOut
            a.addAuthStateListener { onUser(it.currentUser) }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase start failed", e)
            _state.value = State.NotConfigured
        }
    }

    // ---------- profiles: the device's guest, or the signed-in account ----------
    //
    // The personal data on screen (history, list, Xtream accounts, favourite channels) is always
    // the current profile's. Signing in puts the guest's aside and shows the account's (the
    // guest's is never copied into the account). Signing out deletes the account's copy from the
    // device (it lives in the account) and brings the guest's back. So nobody using the device
    // after you sees what you watched.

    private val profile: String get() = prefs.getString(KEY_PROFILE, GUEST) ?: GUEST

    /** Called when the device changes person (sign in / out): their own links (YouTube) go. */
    var onProfileChange: (() -> Unit)? = null

    private suspend fun switchProfile(to: String, discardCurrent: Boolean) {
        val from = profile
        if (from == to) return
        onProfileChange?.invoke()
        history.switchProfile(from, to, discardCurrent)
        stats?.switchProfile(from, to)
        stats?.clearRemote()
        store.switchWatchProfile(from, to, discardCurrent)
        settings.switchProfile(from, to, discardCurrent)
        prefs.edit().putString(KEY_PROFILE, to).commit()
    }

    /** True when this device shows the guest's data (nobody signed in). */
    val isGuest: Boolean get() = profile == GUEST

    /** Clears the guest's history, list, Xtream accounts and favourite channels on this device. */
    suspend fun clearGuestData() {
        if (!isGuest) return
        history.clearAll()
        store.clearWatchlist()
        settings.clearProfile()
    }

    private fun onUser(user: FirebaseUser?) {
        // Versions before profiles kept no profile: whatever is on the device belongs to whoever
        // is signed in right now (or to the guest), so it is labelled, not moved.
        if (!prefs.contains(KEY_PROFILE)) {
            prefs.edit().putString(KEY_PROFILE, if (user == null) GUEST else "u:${user.uid}").commit()
        }
        if (user == null) {
            stopListening()
            uid = null
            _state.value = if (auth != null) State.SignedOut else State.NotConfigured
            if (!isGuest) scope.launch { switchProfile(GUEST, discardCurrent = true) }
            return
        }
        _state.value = State.SignedIn(user.displayName, user.email, user.photoUrl?.toString())
        if (uid == user.uid) return
        uid = user.uid
        val mine = "u:${user.uid}"
        scope.launch {
            // Another account's copy (signed in without signing out) is not kept either.
            if (profile != mine) switchProfile(mine, discardCurrent = !isGuest)
            if (uid == user.uid) startSyncing(user.uid)
        }
    }

    // ---------- signing in ----------

    /** Phone: the usual "Sign in with Google" sheet (Credential Manager). */
    suspend fun signInWithGoogle(activity: Activity) {
        val cfg = config ?: throw CloudException("إعدادات الحساب ما وصلت من موقعك بعد")
        if (cfg.googleWebClientId.isBlank()) throw CloudException("GOOGLE_WEB_CLIENT_ID ناقص في إعدادات موقعك على Render")
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(cfg.googleWebClientId).build())
            .build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            throw CloudException("انلغى تسجيل الدخول")
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Google sign-in failed", e)
            throw CloudException("ما ضبط تسجيل الدخول بـ Google على هذا الجهاز. جرّب «دخول برمز».")
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw CloudException("رد غير متوقع من Google")
        }
        signInWithIdToken(GoogleIdTokenCredential.createFrom(credential.data).idToken)
    }

    // ---------- email + password ----------

    /** Firebase's error for an email / password step, in plain Arabic. */
    private fun emailMessage(e: Exception): String {
        if (e is com.google.firebase.FirebaseNetworkException) return "تأكد من الإنترنت وجرّب"
        return when ((e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode) {
            "ERROR_INVALID_EMAIL" -> "الإيميل مكتوب غلط"
            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND" ->
                "الإيميل أو كلمة المرور غلط. لو سجّلت بـ Google قبل، اضغط «نسيت كلمة المرور» عشان تفعّل الدخول بكلمة مرور."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "هذا الإيميل مسجّل من قبل. اضغط «دخول»، أو «نسيت كلمة المرور» لو سجّلت بـ Google."
            "ERROR_WEAK_PASSWORD" -> "كلمة المرور ضعيفة، اكتب ٦ أحرف على الأقل"
            "ERROR_OPERATION_NOT_ALLOWED" -> "الدخول بالإيميل مو مفعّل في Firebase (Authentication ← Sign-in method ← Email/Password)"
            "ERROR_TOO_MANY_REQUESTS" -> "محاولات كثيرة، انتظر شوي وجرّب"
            "ERROR_USER_DISABLED" -> "هذا الحساب موقوف"
            else -> "ما ضبط الدخول: ${e.message ?: "خطأ"}"
        }
    }

    private fun emailAuth(): com.google.firebase.auth.FirebaseAuth =
        auth ?: throw CloudException("إعدادات الحساب ما وصلت من موقعك بعد")

    /** Email + password. Same account as Google when the email is the same, so the data follows you. */
    suspend fun signInWithEmail(email: String, password: String) {
        val mail = email.trim()
        if (mail.isEmpty()) throw CloudException("اكتب الإيميل")
        if (password.isEmpty()) throw CloudException("اكتب كلمة المرور")
        try {
            emailAuth().signInWithEmailAndPassword(mail, password).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "email sign-in failed", e)
            throw CloudException(emailMessage(e))
        }
    }

    suspend fun signUpWithEmail(email: String, password: String) {
        val mail = email.trim()
        if (mail.isEmpty()) throw CloudException("اكتب الإيميل")
        if (password.isEmpty()) throw CloudException("اكتب كلمة المرور")
        try {
            emailAuth().createUserWithEmailAndPassword(mail, password).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "email sign-up failed", e)
            throw CloudException(emailMessage(e))
        }
    }

    /** Mails a link to set a new password (this also switches password sign-in on for an account that only used Google). */
    suspend fun sendPasswordReset(email: String) {
        val mail = email.trim()
        if (mail.isEmpty()) throw CloudException("اكتب إيميلك أول")
        try {
            emailAuth().sendPasswordResetEmail(mail).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "password reset failed", e)
            throw CloudException(emailMessage(e))
        }
    }

    /** TV (or any device): asks the web app for a code to enter at google.com/device. */
    private fun parseObject(body: String): kotlinx.serialization.json.JsonObject? =
        runCatching { json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()

    suspend fun startDeviceLogin(): DeviceCode {
        val body = postJson("/auth/device/start", "{}")
        // Render's free server may still be waking up (it answers with its own page meanwhile).
        val o = parseObject(body) ?: throw CloudException("موقعك لسا يصحى، انتظر دقيقة وجرّب مرة ثانية")
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (str("userCode").isBlank()) throw CloudException("الدخول برمز غير مفعّل في موقعك (GOOGLE_TV_CLIENT_ID)")
        return DeviceCode(str("deviceCode"), str("userCode"), str("verificationUrl").ifBlank { "https://www.google.com/device" },
            str("expiresIn").toIntOrNull() ?: 1800, str("interval").toIntOrNull() ?: 5)
    }

    /** Waits until the code is approved (true), or refused / expired (false). */
    suspend fun finishDeviceLogin(code: DeviceCode): Boolean {
        var wait = code.interval.coerceAtLeast(3) * 1000L
        val until = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(wait)
            val body = runCatching { postJson("/auth/device/poll", """{"deviceCode":"${code.deviceCode}"}""") }.getOrNull() ?: continue
            val o = parseObject(body) ?: continue
            when ((o["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                "ok" -> {
                    signInWithIdToken((o["idToken"] as kotlinx.serialization.json.JsonPrimitive).content)
                    return true
                }
                "slow_down" -> wait += 2000
                "denied", "expired" -> return false
            }
        }
        return false
    }

    private suspend fun postJson(path: String, body: String): String = withContext(Dispatchers.IO) {
        val url = settings.webUrl.value.trimEnd('/') + path
        try {
            http.newCall(
                Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { res ->
                if (res.code == 501) throw CloudException("الدخول برمز غير مفعّل في موقعك (GOOGLE_TV_CLIENT_ID)")
                res.body?.string().orEmpty()
            }
        } catch (e: java.io.IOException) {
            throw CloudException("ما قدرنا نوصل لموقعك، تأكد من الرابط والإنترنت")
        }
    }

    // ---------- "scan to sign in" ----------

    /** A link opened from a TV's QR code, waiting for this phone's answer (see MainActivity). */
    private val _pendingPairing = MutableStateFlow<String?>(null)
    val pendingPairing: StateFlow<String?> = _pendingPairing.asStateFlow()

    fun openPairingLink(id: String?) {
        if (!id.isNullOrBlank()) _pendingPairing.value = id
    }

    fun dismissPairing() {
        _pendingPairing.value = null
    }

    /** TV: starts a sign-in to show as a QR code. */
    suspend fun startPairing(): Pairing {
        val o = parseObject(postJson("/pair/start", """{"device":${kotlinx.serialization.json.JsonPrimitive(deviceName)}}"""))
            ?: throw CloudException("موقعك لسا يصحى، انتظر دقيقة وجرّب مرة ثانية")
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (str("id").isBlank()) throw CloudException("الدخول بالمسح غير مفعّل في موقعك (FIREBASE_SERVICE_ACCOUNT)")
        return Pairing(str("id"), str("secret"), str("url"), str("expiresIn").toIntOrNull() ?: 600)
    }

    /** TV: waits for the phone. True once signed in; false when refused or expired. */
    suspend fun finishPairing(p: Pairing): Boolean {
        val until = System.currentTimeMillis() + p.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(2_000)
            val body = runCatching { postJson("/pair/poll", """{"id":"${p.id}","secret":"${p.secret}"}""") }.getOrNull() ?: continue
            val o = parseObject(body) ?: continue
            when ((o["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                "approved" -> {
                    val token = (o["token"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return false
                    val a = auth ?: return false
                    try {
                        a.signInWithCustomToken(token).await()
                    } catch (e: Exception) {
                        Log.w(TAG, "custom token sign-in failed", e)
                        throw CloudException("ما قبل Firebase الدخول: ${e.message ?: "خطأ"}")
                    }
                    return true
                }
                "denied", "expired" -> return false
            }
        }
        return false
    }

    /** Phone: which device is asking (null when the link expired). */
    suspend fun pairingDevice(id: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(settings.webUrl.value.trimEnd('/') + "/pair/info?id=" + id).build()).execute().use { res ->
                val o = parseObject(res.body?.string().orEmpty()) ?: return@use null
                if ((o["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "pending") null
                else (o["device"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        }.getOrNull()
    }

    /** Phone: lets the device in to this account ([approve]) or refuses it. */
    suspend fun answerPairing(id: String, approve: Boolean) {
        val user = auth?.currentUser ?: throw CloudException("سجّل دخولك في هذا الجوال أول")
        val idToken = user.getIdToken(false).await().token ?: throw CloudException("ما قدرنا نتأكد من حسابك")
        val body = postJson("/pair/approve", """{"id":"$id","idToken":"$idToken","approve":$approve}""")
        val status = (parseObject(body)?.get("status") as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (approve && status != "approved") throw CloudException("انتهت صلاحية الرمز، اطلب رمز جديد من التلفاز")
    }

    private suspend fun signInWithIdToken(idToken: String) {
        val a = auth ?: throw CloudException("إعدادات الحساب ما وصلت من موقعك بعد")
        try {
            a.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sign-in failed", e)
            throw CloudException("ما قبل Firebase الدخول: ${e.message ?: "خطأ"}")
        }
    }

    fun signOut() {
        // Positions still waiting for their 10-second turn go out first.
        pendingHistory.keys.toList().forEach { key ->
            historyJobs.remove(key)?.cancel()
            sendHistory(key)
        }
        auth?.signOut()
        scope.launch {
            runCatching { androidx.credentials.CredentialManager.create(context).clearCredentialState(androidx.credentials.ClearCredentialStateRequest()) }
        }
    }

    // ---------- watch statistics ----------

    private var statsJob: Job? = null

    /** The counters change every few seconds while playing: upload at most once in a while. */
    private fun scheduleStatsPush() {
        if (userDoc() == null || statsJob?.isActive == true) return
        statsJob = scope.launch {
            delay(STATS_EVERY_MS)
            pushStats()
        }
    }

    private fun pushStats() {
        val s = stats ?: return
        val ref = userDoc()?.collection("stats")?.document(s.deviceId) ?: return
        ref.set(mapOf("json" to s.exportMine(), "updatedAt" to System.currentTimeMillis(), "device" to deviceName))
            .addOnFailureListener { Log.w(TAG, "stats push failed", it) }
    }

    // ---------- syncing ----------

    private fun userDoc() = uid?.let { db?.collection("users")?.document(it) }

    // ---------- admin (the owner's account only; Firestore rules enforce it too) ----------

    private val _openReports = MutableStateFlow(0)
    /** Problem reports nobody marked «تم» yet (the owner's account only). */
    val openReports: StateFlow<Int> = _openReports.asStateFlow()

    data class AdminUser(val uid: String, val email: String?, val name: String?, val lastSeen: Long, val device: String?, val photo: String? = null)

    /** True for the owner's account. The real lock is in firestore.rules; this only shows the page. */
    val isAdmin: Boolean
        get() = (state.value as? State.SignedIn)?.email.equals(ADMIN_EMAIL, ignoreCase = true)

    /** Every person who opened the app since profiles were added, newest first. */
    suspend fun adminUsers(): List<AdminUser> {
        val docs = db?.collection("users")?.get()?.await()?.documents.orEmpty()
        return docs.mapNotNull { d ->
            if (d.getString("email") == null && d.getLong("lastSeen") == null) return@mapNotNull null
            AdminUser(d.id, d.getString("email"), d.getString("name"), d.getLong("lastSeen") ?: 0, d.getString("device"), d.getString("photo"))
        }.sortedByDescending { it.lastSeen }
    }

    /** One person's history as they see it (newest first, removed entries left out). */
    suspend fun adminHistory(uid: String): List<WatchEntry> {
        val docs = db?.collection("users")?.document(uid)?.collection("history")?.get()?.await()?.documents.orEmpty()
        return docs.filter { it.getBoolean("deleted") != true }.mapNotNull { it.toEntry() }.sortedByDescending { it.updatedAt }
    }

    /** Everything in one person's account except the history, as it is stored. */
    data class AdminProfile(
        val list: List<WatchItem>,
        val accounts: List<XtreamAccount>,
        val koraUrl: String?,
        val vipUser: String?,
        val vipPass: String?,
        val devices: List<String>,
        val byKind: Map<String, Long>,
        val topTitles: List<Pair<String, Long>>,
        val finished: Int,
    )

    suspend fun adminProfile(uid: String): AdminProfile {
        val base = db?.collection("users")?.document(uid) ?: error("no db")
        val list = base.collection("watchlist").get().await().documents
            .filter { it.getBoolean("deleted") != true }.mapNotNull { it.toWatchItem() }
            .sortedByDescending { it.updatedAt }
        val accounts = runCatching { base.collection("meta").document("xtream").get().await().toAccounts() }.getOrDefault(emptyList())
        val kora = runCatching { base.collection("meta").document("kora").get().await() }.getOrNull()
        val m = java.time.YearMonth.now().toString()
        val kinds = mutableMapOf<String, Long>()
        val titles = mutableMapOf<String, Long>()
        val done = mutableSetOf<String>()
        val devices = mutableListOf<String>()
        base.collection("stats").get().await().documents.forEach { d ->
            d.getString("device")?.let { devices += it }
            val month = runCatching { org.json.JSONObject(d.getString("json").orEmpty()).optJSONObject(m) }.getOrNull() ?: return@forEach
            month.optJSONObject("kind")?.let { o -> o.keys().forEach { k -> kinds.merge(k, o.optLong(k), Long::plus) } }
            month.optJSONObject("title")?.let { o -> o.keys().forEach { k -> titles.merge(k, o.optLong(k), Long::plus) } }
            month.optJSONArray("done")?.let { arr -> for (i in 0 until arr.length()) done += arr.optString(i) }
        }
        return AdminProfile(
            list, accounts, kora?.getString("url"), kora?.getString("vipUser"), kora?.getString("vipPass"), devices.distinct(),
            kinds, titles.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }, done.size,
        )
    }

    /** Removes entries from that person's account (their devices drop them on the next sync). */
    suspend fun adminRemove(uid: String, keys: List<String>) {
        val col = db?.collection("users")?.document(uid)?.collection("history") ?: return
        val at = System.currentTimeMillis()
        keys.forEach { col.document(docId(it)).set(mapOf("key" to it, "deleted" to true, "updatedAt" to at)).await() }
    }

    // ---- problem reports (anyone signed in sends; only the owner reads)

    data class Report(val id: String, val uid: String, val email: String?, val name: String?, val text: String,
        val problem: String?, val device: String?, val version: String?, val at: Long, val done: Boolean,
        val place: String? = null, val targetType: String? = null, val targetId: Int? = null)

    /** The last playback failure on this device, attached to the next report so nobody has to describe it. */
    fun noteProblem(text: String) {
        prefs.edit().putString("last_problem", text).putLong("last_problem_at", System.currentTimeMillis()).apply()
    }

    fun lastProblem(): String? {
        val at = prefs.getLong("last_problem_at", 0)
        return prefs.getString("last_problem", null)?.takeIf { System.currentTimeMillis() - at < 24 * 3600_000 }
    }

    suspend fun sendReport(text: String, withProblem: Boolean, place: String? = null, targetType: String? = null, targetId: Int? = null) {
        val u = auth?.currentUser ?: throw CloudException("سجّل دخولك أول عشان توصل رسالتك")
        val col = db?.collection("reports") ?: throw CloudException("الخدمة مب جاهزة")
        col.add(mapOf(
            "uid" to u.uid, "email" to u.email, "name" to u.displayName, "text" to text.trim(),
            "problem" to if (withProblem) lastProblem() else null,
            "device" to deviceName, "version" to com.alyoushw.watchhub.BuildConfig.VERSION_NAME,
            "at" to System.currentTimeMillis(), "done" to false,
            "place" to place, "targetType" to targetType, "targetId" to targetId,
        )).await()
    }

    suspend fun adminReports(): List<Report> =
        db?.collection("reports")?.get()?.await()?.documents.orEmpty().map { d ->
            Report(d.id, d.getString("uid").orEmpty(), d.getString("email"), d.getString("name"), d.getString("text").orEmpty(),
                d.getString("problem"), d.getString("device"), d.getString("version"), d.getLong("at") ?: 0, d.getBoolean("done") == true,
                d.getString("place"), d.getString("targetType"), d.getLong("targetId")?.toInt())
        }.sortedByDescending { it.at }

    suspend fun adminReportDone(id: String, done: Boolean) { db?.collection("reports")?.document(id)?.update("done", done)?.await() }
    suspend fun adminReportDelete(id: String) { db?.collection("reports")?.document(id)?.delete()?.await() }

    private fun pushProfile(base: com.google.firebase.firestore.DocumentReference) {
        val u = auth?.currentUser ?: return
        base.set(
            mapOf("email" to u.email, "name" to u.displayName, "lastSeen" to System.currentTimeMillis(), "device" to deviceName, "photo" to u.photoUrl?.toString()),
            SetOptions.merge(),
        ).addOnFailureListener { Log.w(TAG, "profile push failed", it) }
    }

    private fun startSyncing(uid: String) {
        stopListening()
        val base = db?.collection("users")?.document(uid) ?: return
        pushProfile(base)
        scope.launch {
            try {
                // 1) First meeting: send what only this device has (or has newer), take the rest.
                mergeHistory(base.collection("history").get().await().documents)
                mergeWatchlist(base.collection("watchlist").get().await().documents)
                mergeXtream(base.collection("meta").document("xtream").get().await())
                mergeKora(base.collection("meta").document("kora").get().await())
                mergeMatchPrefs(base.collection("meta").document("matches").get().await())
                applyMarks(base.collection("meta").document("seriesMarks").get().await())
                marks?.seriesKeys()?.takeIf { it.isNotEmpty() }?.let(::pushMarks)
                history.mergeHidden(hiddenOf(base.collection("meta").document("continueHidden").get().await()))
                history.continueHidden.value.takeIf { it.isNotEmpty() }?.let(::pushHidden)
                recommender?.let { rec ->
                    rec.mergeDismissed(hiddenOf(base.collection("meta").document("dismissed").get().await()).keys)
                    rec.dismissedKeys().takeIf { it.isNotEmpty() }?.let(::pushDismissed)
                }
                _lastSync.value = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "first sync failed", e)
            }
            if (this@CloudSync.uid != uid) return@launch
            // 2) From now on, every change from another device arrives within a second or so.
            pushStats()
            if (isAdmin) {
                // The owner is told inside the app when someone sends a problem report.
                val startedAt = System.currentTimeMillis()
                listeners += db?.collection("reports")?.whereEqualTo("done", false)?.addSnapshotListener { snap, e ->
                    if (e != null || snap == null) return@addSnapshotListener
                    _openReports.value = snap.size()
                    val fresh = snap.documentChanges.count {
                        it.type == com.google.firebase.firestore.DocumentChange.Type.ADDED && (it.document.getLong("at") ?: 0) > startedAt - 5_000
                    }
                    if (fresh > 0) android.widget.Toast.makeText(context, "📨 وصلك بلاغ جديد من مستخدم (لوحة المدير)", android.widget.Toast.LENGTH_LONG).show()
                }.let { listOfNotNull(it) }.orEmpty()
            }
            listeners += base.collection("stats").addSnapshotListener { snap, e ->
                if (e != null || snap == null) return@addSnapshotListener
                snap.documentChanges.forEach { change ->
                    val id = change.document.id
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) stats?.setRemote(id, null)
                    else stats?.setRemote(id, change.document.getString("json"))
                }
            }
            listeners += base.collection("history").addSnapshotListener { snap, e ->
                if (e != null || snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                var any = false
                snap.documentChanges.forEach { any = applyHistoryDoc(it.document) || any }
                if (any || !snap.metadata.isFromCache) _lastSync.value = System.currentTimeMillis()
            }
            listeners += base.collection("watchlist").addSnapshotListener { snap, e ->
                if (e != null || snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                scope.launch { snap.documentChanges.forEach { applyWatchDoc(it.document) } }
                _lastSync.value = System.currentTimeMillis()
            }
            listeners += base.collection("meta").document("xtream").addSnapshotListener { snap, e ->
                if (e != null || snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                applyXtreamDoc(snap)
            }
            listeners += base.collection("meta").document("continueHidden").addSnapshotListener { snap, e ->
                if (e != null || snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                history.mergeHidden(hiddenOf(snap))
            }
            listeners += base.collection("meta").document("dismissed").addSnapshotListener { snap, e ->
                if (e != null || snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                recommender?.mergeDismissed(hiddenOf(snap).keys)
            }
            listeners += base.collection("meta").document("kora").addSnapshotListener { snap, e ->
                if (e != null || snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                applyKora(snap)
            }
            listeners += base.collection("meta").document("matches").addSnapshotListener { snap, e ->
                if (e != null || snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                applyMatchPrefs(snap)
            }
            listeners += base.collection("meta").document("seriesMarks").addSnapshotListener { snap, e ->
                if (e != null || snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                applyMarks(snap)
            }
        }
    }

    private fun stopListening() {
        listeners.forEach { it.remove() }
        listeners.clear()
        pendingHistory.clear()
        historyJobs.values.forEach { it.cancel() }
        historyJobs.clear()
    }

    // ---- history

    private fun docId(key: String) = key.replace('/', '_')

    /** Set once the recommender exists: its «مو لي» list is synced like the removals below. */
    var recommender: Recommender? = null
        set(value) {
            field = value
            value?.onDismiss = { key -> pushDismissed(setOf(key)) }
        }

    /** Set once the player's learned marks exist, so intro/credits sync across devices. */
    var marks: com.alyoushw.watchhub.player.SeriesMarks? = null
        set(value) {
            field = value
            value?.onChange = { series -> pushMarks(setOf(series)) }
        }

    private fun pushMarks(series: Set<String>) {
        val m = marks ?: return
        val ref = userDoc()?.collection("meta")?.document("seriesMarks") ?: return
        val data = series.associate { s ->
            val r = m.record(s)
            hiddenField(s) to org.json.JSONObject(mapOf(
                "intro" to r.intro, "introAt" to r.introAt, "credits" to r.credits, "creditsAt" to r.creditsAt,
            )).toString()
        }
        if (data.isEmpty()) return
        scope.launch { runCatching { ref.set(data, SetOptions.merge()).await() }.onFailure { Log.w(TAG, "marks sync failed", it) } }
    }

    private fun applyMarks(doc: DocumentSnapshot) {
        val m = marks ?: return
        doc.data.orEmpty().forEach { (field, value) ->
            val series = runCatching { String(android.util.Base64.decode(field, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)) }.getOrNull() ?: return@forEach
            val o = runCatching { org.json.JSONObject(value as String) }.getOrNull() ?: return@forEach
            m.applyRemote(series, o.optString("intro", null.toString()).takeIf { it != "null" && it.isNotBlank() },
                o.optLong("introAt"), o.optLong("credits", -1), o.optLong("creditsAt"))
        }
    }

    private fun applyKora(doc: DocumentSnapshot) {
        if (!doc.exists()) return
        doc.getString("url")?.let { settings.setKoraUrlFromCloud(it, doc.getLong("urlAt") ?: 0) }
        val vipUser = doc.getString("vipUser")
        if (vipUser != null) settings.setKoraVipFromCloud(vipUser, doc.getString("vipPass").orEmpty(), doc.getLong("vipAt") ?: 0)
    }

    private suspend fun mergeKora(doc: DocumentSnapshot) {
        applyKora(doc)
        // Send what this device has that the cloud is missing or older than.
        val urlNewer = settings.koraUrl.value.isNotBlank() && settings.koraUrlUpdatedAt > (doc.getLong("urlAt") ?: 0)
        val urlMissing = settings.koraUrl.value.isNotBlank() && doc.getString("url").isNullOrBlank()
        val vipNewer = settings.koraVipUser.value.isNotBlank() && settings.koraVipUpdatedAt > (doc.getLong("vipAt") ?: 0)
        val vipMissing = settings.koraVipUser.value.isNotBlank() && doc.getString("vipUser").isNullOrBlank()
        if (urlNewer || urlMissing || vipNewer || vipMissing) pushKora()
    }

    private fun applyMatchPrefs(doc: DocumentSnapshot) {
        if (!doc.exists()) return
        val at = doc.getLong("at") ?: return
        fun set(k: String) = doc.getString(k).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        settings.setMatchPrefsFromCloud(set("leagues"), set("teams"), at)
    }

    private fun mergeMatchPrefs(doc: DocumentSnapshot) {
        applyMatchPrefs(doc)
        val newer = settings.matchPrefsUpdatedAt > (doc.getLong("at") ?: 0)
        if (newer) pushMatchPrefs()
    }

    private fun pushMatchPrefs() {
        val ref = userDoc()?.collection("meta")?.document("matches") ?: return
        val data = mapOf(
            "leagues" to settings.matchLeagues.value.joinToString(","),
            "teams" to settings.matchTeams.value.joinToString(","),
            "at" to settings.matchPrefsUpdatedAt,
        )
        scope.launch { runCatching { ref.set(data).await() }.onFailure { Log.w(TAG, "match prefs sync failed", it) } }
    }

    private fun pushKora() {
        val ref = userDoc()?.collection("meta")?.document("kora") ?: return
        val data = buildMap<String, Any> {
            settings.koraUrl.value.takeIf { it.isNotBlank() }?.let { put("url", it); put("urlAt", settings.koraUrlUpdatedAt) }
            settings.koraVipUser.value.takeIf { it.isNotBlank() }?.let {
                put("vipUser", it); put("vipPass", settings.koraVipPass.value); put("vipAt", settings.koraVipUpdatedAt)
            }
        }
        if (data.isEmpty()) return
        scope.launch { runCatching { ref.set(data, SetOptions.merge()).await() }.onFailure { Log.w(TAG, "kora sync failed", it) } }
    }

    private fun pushDismissed(keys: Set<String>) {
        val ref = userDoc()?.collection("meta")?.document("dismissed") ?: return
        scope.launch {
            runCatching { ref.set(keys.associate { hiddenField(it) to System.currentTimeMillis() }, SetOptions.merge()).await() }
                .onFailure { Log.w(TAG, "dismissed sync failed", it) }
        }
    }

    // «تابع المشاهدة» removals: one document, field = base64 of the group key (keys hold ':' and '.'), value = when.
    private fun hiddenField(key: String) = android.util.Base64.encodeToString(key.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)

    private fun hiddenOf(doc: DocumentSnapshot): Map<String, Long> = doc.data.orEmpty().mapNotNull { (field, value) ->
        val key = runCatching { String(android.util.Base64.decode(field, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)) }.getOrNull()
        val at = (value as? Number)?.toLong()
        if (key == null || at == null) null else key to at
    }.toMap()

    private fun pushHidden(items: Map<String, Long>) {
        val ref = userDoc()?.collection("meta")?.document("continueHidden") ?: return
        scope.launch {
            runCatching { ref.set(items.entries.associate { hiddenField(it.key) to it.value }, SetOptions.merge()).await() }
                .onFailure { Log.w(TAG, "hidden sync failed", it) }
        }
    }

    private fun WatchEntry.toMap(): Map<String, Any?> = mapOf(
        "key" to key, "kind" to kind, "itemId" to itemId, "title" to title, "subtitle" to subtitle,
        "image" to image, "ext" to ext, "seriesId" to seriesId, "positionMs" to positionMs,
        "durationMs" to durationMs, "updatedAt" to updatedAt, "accountId" to accountId, "tmdbId" to tmdbId,
        "deleted" to false, "device" to deviceName,
        "animeRef" to animeRef?.let { json.encodeToString(AnimeEpisodeRef.serializer(), it) },
        "sourceRef" to sourceRef?.let { json.encodeToString(WatchSourceRef.serializer(), it) },
        "fourURef" to fourURef?.let { json.encodeToString(FourURef.serializer(), it) },
    )

    private fun DocumentSnapshot.toEntry(): WatchEntry? {
        val key = getString("key") ?: return null
        return WatchEntry(
            key = key,
            kind = getString("kind") ?: return null,
            itemId = getString("itemId") ?: return null,
            title = getString("title").orEmpty(),
            subtitle = getString("subtitle"),
            image = getString("image"),
            ext = getString("ext"),
            seriesId = getString("seriesId"),
            positionMs = getLong("positionMs") ?: 0,
            durationMs = getLong("durationMs") ?: 0,
            updatedAt = getLong("updatedAt") ?: 0,
            accountId = getString("accountId"),
            tmdbId = getLong("tmdbId")?.toInt(),
            fourURef = getString("fourURef")?.let { raw ->
                runCatching { json.decodeFromString(FourURef.serializer(), raw) }.getOrNull()
            },
            sourceRef = getString("sourceRef")?.let { raw ->
                runCatching { json.decodeFromString(WatchSourceRef.serializer(), raw) }.getOrNull()
            },
            animeRef = getString("animeRef")?.let { raw ->
                runCatching { json.decodeFromString(AnimeEpisodeRef.serializer(), raw) }.getOrNull()
            },
        )
    }

    private fun applyHistoryDoc(doc: DocumentSnapshot): Boolean {
        val key = doc.getString("key") ?: return false
        val at = doc.getLong("updatedAt") ?: return false
        return if (doc.getBoolean("deleted") == true) history.applyRemote(key, null, at)
        else history.applyRemote(key, doc.toEntry() ?: return false, at)
    }

    private suspend fun mergeHistory(remote: List<DocumentSnapshot>) {
        val byKey = remote.associateBy { it.getString("key") }
        // All the account's entries at once (one disk write, not one per entry).
        history.applyRemoteBatch(remote.mapNotNull { doc ->
            val key = doc.getString("key") ?: return@mapNotNull null
            val at = doc.getLong("updatedAt") ?: return@mapNotNull null
            if (doc.getBoolean("deleted") == true) Triple(key, null, at)
            else doc.toEntry()?.let { Triple(key, it, at) }
        })
        val base = userDoc() ?: return
        history.entries.value.forEach { e ->
            val r = byKey[e.key]
            if (r == null || (r.getLong("updatedAt") ?: 0) < e.updatedAt) {
                base.collection("history").document(docId(e.key)).set(e.toMap()).await()
            }
        }
        history.removedKeys().forEach { (key, at) ->
            val r = byKey[key]
            if (r != null && r.getBoolean("deleted") != true && (r.getLong("updatedAt") ?: 0) < at) {
                base.collection("history").document(docId(key)).set(mapOf("key" to key, "deleted" to true, "updatedAt" to at)).await()
            }
        }
    }

    private val pendingHistory = mutableMapOf<String, Pair<WatchEntry?, Long?>>()
    private val historyJobs = mutableMapOf<String, Job>()
    private val lastHistoryPush = mutableMapOf<String, Long>()

    /**
     * Sends a history change. Starting, pausing, stopping and removing go out at once; the
     * player's periodic position saves go out at most every [PROGRESS_EVERY_MS] per title.
     */
    private fun pushHistory(entry: WatchEntry?, key: String, removedAt: Long?, important: Boolean) {
        if (userDoc() == null) return
        scope.launch {
            pendingHistory[key] = entry to removedAt
            val since = System.currentTimeMillis() - (lastHistoryPush[key] ?: 0)
            if (important || since >= PROGRESS_EVERY_MS) {
                historyJobs.remove(key)?.cancel()
                sendHistory(key)
            } else if (historyJobs[key]?.isActive != true) {
                historyJobs[key] = scope.launch {
                    delay(PROGRESS_EVERY_MS - since)
                    sendHistory(key)
                }
            }
        }
    }

    private fun sendHistory(key: String) {
        val (entry, removedAt) = pendingHistory.remove(key) ?: return
        val ref = userDoc()?.collection("history")?.document(docId(key)) ?: return
        lastHistoryPush[key] = System.currentTimeMillis()
        val data = entry?.toMap() ?: mapOf("key" to key, "deleted" to true, "updatedAt" to (removedAt ?: System.currentTimeMillis()))
        ref.set(data).addOnSuccessListener { _lastSync.value = System.currentTimeMillis() }
            .addOnFailureListener { Log.w(TAG, "history push failed", it) }
    }

    /**
     * Right before playing: the newest position from the account (another device may have
     * moved on in the last seconds). Waits at most [timeoutMs]; the local copy is used otherwise.
     */
    suspend fun refreshEntry(key: String, timeoutMs: Long = 2_000) {
        val ref = userDoc()?.collection("history")?.document(docId(key)) ?: return
        withTimeoutOrNull(timeoutMs) {
            runCatching { ref.get().await() }.getOrNull()?.takeIf { it.exists() }?.let { applyHistoryDoc(it) }
        }
    }

    // ---- watchlist

    private fun WatchItem.toMap(): Map<String, Any?> = mapOf(
        "key" to key,
        "type" to type.path,
        "id" to id,
        "title" to title,
        "poster" to posterPath,
        "status" to when (status) {
            WatchStatus.PLANNED -> "want"
            WatchStatus.WATCHING -> "watching"
            WatchStatus.DONE -> "done"
        },
        "progressSeason" to progress?.season,
        "progressEpisode" to progress?.episode,
        "addedAt" to addedAt,
        "updatedAt" to updatedAt,
        "deleted" to false,
        // Everything else this app keeps (seasons, links…), for other Android devices.
        "android" to json.encodeToString(WatchItem.serializer(), this),
    )

    private fun DocumentSnapshot.toWatchItem(): WatchItem? {
        val season = getLong("progressSeason")?.toInt()
        val episode = getLong("progressEpisode")?.toInt()
        val status = when (getString("status")) {
            "watching" -> WatchStatus.WATCHING
            "done" -> WatchStatus.DONE
            else -> WatchStatus.PLANNED
        }
        val progress = if (season != null && episode != null) EpisodeMark(season, episode) else null
        // The top-level fields are what every device edits (the web app only changes those),
        // so they win over the Android-only copy kept alongside.
        getString("android")?.let { raw ->
            runCatching { json.decodeFromString(WatchItem.serializer(), raw) }.getOrNull()?.let {
                return it.copy(status = status, progress = progress ?: it.progress, title = getString("title") ?: it.title)
            }
        }
        val type = MediaType.fromPath(getString("type")) ?: return null
        val id = getLong("id")?.toInt() ?: return null
        return WatchItem(
            id = id,
            type = type,
            title = getString("title").orEmpty(),
            posterPath = getString("poster"),
            status = status,
            progress = progress,
            addedAt = getLong("addedAt") ?: System.currentTimeMillis(),
        )
    }

    private suspend fun applyWatchDoc(doc: DocumentSnapshot) {
        val key = doc.getString("key") ?: return
        val at = doc.getLong("updatedAt") ?: return
        if (doc.getBoolean("deleted") == true) store.applyRemoteWatch(key, null, at)
        else store.applyRemoteWatch(key, doc.toWatchItem() ?: return, at)
    }

    private suspend fun mergeWatchlist(remote: List<DocumentSnapshot>) {
        val byKey = remote.associateBy { it.getString("key") }
        remote.forEach { applyWatchDoc(it) }
        val base = userDoc() ?: return
        val data = store.data.value
        data.watchlist.forEach { item ->
            val r = byKey[item.key]
            // Items from before syncing existed carry no time: use when they were added.
            val stamped = if (item.updatedAt == 0L) item.copy(updatedAt = item.addedAt) else item
            if (r == null || (r.getLong("updatedAt") ?: 0) < stamped.updatedAt) {
                base.collection("watchlist").document(docId(item.key)).set(stamped.toMap()).await()
            }
        }
        data.removedWatch.forEach { (key, at) ->
            val r = byKey[key]
            if (r != null && r.getBoolean("deleted") != true && (r.getLong("updatedAt") ?: 0) < at) {
                base.collection("watchlist").document(docId(key)).set(mapOf("key" to key, "deleted" to true, "updatedAt" to at)).await()
            }
        }
    }

    private fun pushWatch(item: WatchItem?, key: String, removedAt: Long?) {
        val ref = userDoc()?.collection("watchlist")?.document(docId(key)) ?: return
        val data = item?.toMap() ?: mapOf("key" to key, "deleted" to true, "updatedAt" to (removedAt ?: System.currentTimeMillis()))
        ref.set(data).addOnSuccessListener { _lastSync.value = System.currentTimeMillis() }
            .addOnFailureListener { Log.w(TAG, "watchlist push failed", it) }
    }

    // ---- Xtream accounts

    private fun XtreamAccount.toMap(): Map<String, Any?> =
        mapOf("id" to id, "name" to name, "host" to host, "user" to username, "pass" to password, "off" to off)

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toAccounts(): List<XtreamAccount> =
        (get("accounts") as? List<Map<String, Any?>>).orEmpty().mapNotNull { m ->
            val host = m["host"] as? String ?: return@mapNotNull null
            val user = m["user"] as? String ?: return@mapNotNull null
            XtreamAccount(host, user, m["pass"] as? String ?: "", XtreamAccount.stableId(host, user), m["name"] as? String ?: "", m["off"] as? Boolean ?: false)
        }

    private fun applyXtreamDoc(doc: DocumentSnapshot) {
        if (!doc.exists()) return
        val at = doc.getLong("updatedAt") ?: return
        if (at <= settings.xtreamAccountsUpdatedAt) return
        settings.setXtreamAccountsFromCloud(doc.toAccounts(), at)
        _lastSync.value = System.currentTimeMillis()
    }

    private suspend fun mergeXtream(doc: DocumentSnapshot) {
        val remoteAt = if (doc.exists()) doc.getLong("updatedAt") ?: 0 else 0
        val local = settings.xtreamAccounts.value
        when {
            remoteAt > settings.xtreamAccountsUpdatedAt -> applyXtreamDoc(doc)
            local.isNotEmpty() -> {
                // Nothing newer in the account: send this device's list (stamped now if it never was).
                val at = settings.xtreamAccountsUpdatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                userDoc()?.collection("meta")?.document("xtream")?.set(mapOf("accounts" to local.map { it.toMap() }, "updatedAt" to at), SetOptions.merge())?.await()
            }
        }
    }

    private fun pushXtream(list: List<XtreamAccount>, at: Long) {
        val ref = userDoc()?.collection("meta")?.document("xtream") ?: return
        ref.set(mapOf("accounts" to list.map { it.toMap() }, "updatedAt" to at))
            .addOnSuccessListener { _lastSync.value = System.currentTimeMillis() }
            .addOnFailureListener { Log.w(TAG, "xtream push failed", it) }
    }

    private companion object {
        const val TAG = "CloudSync"
        const val ADMIN_EMAIL = "alyoushw@gmail.com"
        const val APP_NAME = "watchhub"
        const val KEY_CONFIG = "config"
        const val KEY_PROFILE = "profile"
        const val STATS_EVERY_MS = 20_000L
        const val GUEST = "guest"
        const val PROGRESS_EVERY_MS = 10_000L
    }
}
