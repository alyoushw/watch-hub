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
        store.onWatchChanged = { item, key, removedAt -> pushWatch(item, key, removedAt) }
        settings.onXtreamAccountsChanged = { list, at -> pushXtream(list, at) }
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

    // ---------- syncing ----------

    private fun userDoc() = uid?.let { db?.collection("users")?.document(it) }

    private fun startSyncing(uid: String) {
        stopListening()
        val base = db?.collection("users")?.document(uid) ?: return
        scope.launch {
            try {
                // 1) First meeting: send what only this device has (or has newer), take the rest.
                mergeHistory(base.collection("history").get().await().documents)
                mergeWatchlist(base.collection("watchlist").get().await().documents)
                mergeXtream(base.collection("meta").document("xtream").get().await())
                _lastSync.value = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "first sync failed", e)
            }
            if (this@CloudSync.uid != uid) return@launch
            // 2) From now on, every change from another device arrives within a second or so.
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

    private fun WatchEntry.toMap(): Map<String, Any?> = mapOf(
        "key" to key, "kind" to kind, "itemId" to itemId, "title" to title, "subtitle" to subtitle,
        "image" to image, "ext" to ext, "seriesId" to seriesId, "positionMs" to positionMs,
        "durationMs" to durationMs, "updatedAt" to updatedAt, "accountId" to accountId, "tmdbId" to tmdbId,
        "deleted" to false, "device" to deviceName,
        "animeRef" to animeRef?.let { json.encodeToString(AnimeEpisodeRef.serializer(), it) },
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
        remote.forEach { applyHistoryDoc(it) }
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
        mapOf("id" to id, "name" to name, "host" to host, "user" to username, "pass" to password)

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toAccounts(): List<XtreamAccount> =
        (get("accounts") as? List<Map<String, Any?>>).orEmpty().mapNotNull { m ->
            val host = m["host"] as? String ?: return@mapNotNull null
            val user = m["user"] as? String ?: return@mapNotNull null
            XtreamAccount(host, user, m["pass"] as? String ?: "", XtreamAccount.stableId(host, user), m["name"] as? String ?: "")
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
        const val APP_NAME = "watchhub"
        const val KEY_CONFIG = "config"
        const val KEY_PROFILE = "profile"
        const val GUEST = "guest"
        const val PROGRESS_EVERY_MS = 10_000L
    }
}
