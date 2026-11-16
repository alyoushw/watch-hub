package com.alyoushw.watchhub.player.casting

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PhoneCastController(private val activity: ComponentActivity, private val player: ExoPlayer,
    private val source: () -> CastSource) {
    var dialog by mutableStateOf(false)
    var dlnaPicker by mutableStateOf(false)
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var devices by mutableStateOf<List<DlnaDevice>>(emptyList()); private set
    var deviceName by mutableStateOf<String?>(null); private set
    var protocol by mutableStateOf(""); private set
    var remotePlaying by mutableStateOf(false); private set
    var remotePosition by mutableStateOf(0L); private set
    var duration by mutableStateOf(0L); private set
    var live by mutableStateOf(false); private set
    val connected get() = deviceName != null
    val relaying get() = connected || relay != null && busy
    private val scope = activity.lifecycleScope
    private val dlna = Dlna(activity.applicationContext)
    private var device: DlnaDevice? = null
    private var cast: CastContext? = null
    private var ownedSession: CastSession? = null
    private var pendingGoogle = false
    private var chooser: MediaRouteChooserDialog? = null
    private var job: Job? = null
    private var polling: Job? = null
    private var binding: ServiceConnection? = null
    private var relay: CastRelayService? = null
    private var wasPlaying = false
    private var closed = false
    private var epoch = 0
    private var offset = 0L

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = ownedSession?.remoteMediaClient ?: return
            remotePosition = client.approximateStreamPosition.coerceAtLeast(0)
            remotePlaying = client.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING
            if (client.mediaStatus?.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                error = "رفض جهاز Google Cast تشغيل الفيديو. قد لا يدعم صيغته أو ترميزه."
                dialog = true
                disconnect()
            }
        }
    }
    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, id: String) { if (pendingGoogle) loadGoogle(session) }
        override fun onSessionResumed(session: CastSession, suspended: Boolean) { if (pendingGoogle) loadGoogle(session) }
        override fun onSessionStartFailed(session: CastSession, code: Int) {
            if (pendingGoogle) { pendingGoogle = false; busy = false; error = "تعذّر الاتصال بجهاز Google Cast ($code)."; dialog = true }
        }
        override fun onSessionEnded(session: CastSession, code: Int) { if (ownedSession == session) disconnect() }
        override fun onSessionResumeFailed(session: CastSession, code: Int) { if (ownedSession == session) disconnect() }
        override fun onSessionStarting(session: CastSession) { if (pendingGoogle) busy = true }
        override fun onSessionResuming(session: CastSession, id: String) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionSuspended(session: CastSession, code: Int) { remotePlaying = false }
    }

    fun google() {
        if (busy || connected) return
        error = null
        try {
            CastLan.find(activity)
            source() // Check readiness before opening discovery.
        } catch (e: Exception) {
            error = e.message ?: "تعذّر تجهيز البث."
            return
        }
        cast?.let { openGoogle(it); return }
        busy = true
        try {
            // The asynchronous form loads the Cast module safely; the synchronous one fails on some phones.
            CastContext.getSharedInstance(activity, ContextCompat.getMainExecutor(activity))
                .addOnSuccessListener(activity) { context ->
                    busy = false
                    if (closed) return@addOnSuccessListener
                    cast = context
                    context.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
                    openGoogle(context)
                }
                .addOnFailureListener(activity) { e ->
                    busy = false
                    error = "تعذّر تشغيل Google Cast على الجوال. حدّث خدمات Google Play ثم أعد المحاولة. (${e.javaClass.simpleName})"
                }
        } catch (e: Exception) {
            busy = false
            error = "تعذّر تشغيل Google Cast على الجوال. حدّث خدمات Google Play ثم أعد المحاولة. (${e.javaClass.simpleName})"
        }
    }

    private fun openGoogle(context: CastContext) {
        pendingGoogle = true
        val session = context.sessionManager.currentCastSession
        if (session?.isConnected == true) { loadGoogle(session); return }
        try {
            val themed = ContextThemeWrapper(activity, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            chooser = MediaRouteChooserDialog(themed).apply {
                routeSelector = MediaRouteSelector.Builder().addControlCategory(
                    CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)).build()
                setOnDismissListener { if (!closed) dialog = true }
                show()
                setTitle("Google Cast · اختر جهازاً") // The title view exists only after show().
            }
            dialog = false
        } catch (e: Exception) {
            pendingGoogle = false
            error = "تعذّر فتح قائمة أجهزة Google Cast. (${e.javaClass.simpleName})"
        }
    }

    private fun loadGoogle(session: CastSession) {
        pendingGoogle = false
        ownedSession = session
        job?.cancel()
        val ticket = ++epoch
        job = scope.launch {
            busy = true
            dialog = true
            try {
                val media = source()
                wasPlaying = player.playWhenReady
                val url = prepare(media)
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, media.title) }
                val info = MediaInfo.Builder(url).setContentType(media.mime).setMetadata(metadata)
                    .setStreamType(if (media.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                    .apply { if (!media.live && media.durationMs > 0) setStreamDuration(media.durationMs) }.build()
                val client = session.remoteMediaClient ?: error("مشغّل Google Cast غير جاهز.")
                val accepted = CompletableDeferred<Boolean>()
                client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
                    .setCurrentTime(if (media.live) 0 else media.positionMs).build()).setResultCallback { accepted.complete(it.status.isSuccess) }
                if (!withTimeout(30_000) { accepted.await() }) error("رفض Google Cast تشغيل هذا الفيديو؛ تحقق من دعم الجهاز لصيغته.")
                client.registerCallback(mediaCallback)
                transferred(media, session.castDevice?.friendlyName ?: "Google Cast", "Google Cast")
            } catch (_: TimeoutCancellationException) { error = "انتهت مهلة بدء البث على Google Cast."; disconnect(resume = false) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message?.takeIf { !it.contains("http", true) } ?: "تعذّر إرسال الفيديو إلى Google Cast."; disconnect(resume = false) }
            finally { if (ticket == epoch) { busy = false; if (!connected) stopRelay() } }
        }
    }

    fun discoverDlna() {
        if (connected || busy) return
        pendingGoogle = false
        job?.cancel()
        val ticket = ++epoch
        dlnaPicker = true
        devices = emptyList()
        error = null
        job = scope.launch {
            busy = true
            try { devices = dlna.discover() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "تعذّر اكتشاف أجهزة DLNA. تأكد من Wi-Fi والسماح باتصال الأجهزة داخل الشبكة." }
            finally { if (ticket == epoch) busy = false }
        }
    }

    fun select(device: DlnaDevice) {
        if (busy || connected) return
        val ticket = ++epoch
        error = null
        job = scope.launch {
            busy = true
            try {
                val media = source()
                wasPlaying = player.playWhenReady
                val flat = media.mime.contains("mpegurl", true)
                val url = prepare(media, flat)
                val start = if (media.live) 0 else media.positionMs
                // A flattened HLS stream already starts at the watch position, so the renderer must not seek.
                val resumed = dlna.load(device, url, media.title, if (flat) "video/mp2t" else media.mime,
                    if (flat) 0L else start, media.durationMs.takeIf { !flat && !media.live } ?: 0L)
                this@PhoneCastController.device = device
                offset = if (flat) start else 0
                transferred(media, device.name, "DLNA")
                if (flat) duration = 0 // No remote seeking inside a flattened stream.
                if (!resumed) error = "بدأ البث من البداية؛ الجهاز لم يقبل الانتقال إلى موضع المشاهدة السابق."
            } catch (_: TimeoutCancellationException) { error = "انتهت مهلة تجهيز البث إلى جهاز DLNA." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message?.takeIf { !it.contains("http", true) } ?: "تعذّر إرسال الفيديو إلى جهاز DLNA."; stopRelay() }
            finally { if (ticket == epoch) { busy = false; if (!connected) stopRelay() } }
        }
    }

    private suspend fun prepare(media: CastSource, flatHls: Boolean = false): String {
        val lan = CastLan.find(activity)
        if (relay == null) {
            val ready = CompletableDeferred<CastRelayService>()
            val intent = Intent(activity, CastRelayService::class.java)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete((binder as CastRelayService.LocalBinder).service) }
                override fun onServiceDisconnected(name: ComponentName) { relay = null; if (connected) disconnect() }
            }
            ContextCompat.startForegroundService(activity, intent)
            binding = connection
            if (!activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)) error("تعذّر بدء خدمة البث.")
            relay = withTimeout(10_000) { ready.await() }
            relay?.onStopCasting = { disconnect() }
        }
        return checkNotNull(relay).open(lan, media, flatHls)
    }

    private fun transferred(media: CastSource, name: String, method: String) {
        deviceName = name
        protocol = method
        live = media.live
        duration = media.durationMs
        remotePosition = media.positionMs
        remotePlaying = true
        player.pause() // Only after the receiver accepts playback.
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog = false
        polling?.cancel()
        polling = scope.launch {
            val acceptedAt = android.os.SystemClock.elapsedRealtime()
            var confirmed = false
            while (connected) {
                delay(2000)
                val client = ownedSession?.remoteMediaClient
                if (client != null) {
                    remotePosition = client.approximateStreamPosition.coerceAtLeast(0)
                    remotePlaying = client.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING
                } else device?.let { target ->
                    try {
                        val status = dlna.status(target)
                        remotePlaying = status.playing
                        if (status.playing) confirmed = true
                        status.positionMs?.let { remotePosition = it + offset }
                        if (!confirmed && android.os.SystemClock.elapsedRealtime() - acceptedAt > 15_000 && status.state in setOf("STOPPED", "NO_MEDIA_PRESENT")) {
                            error = "جهاز DLNA لم يبدأ تشغيل الفيديو؛ قد لا يدعم صيغته أو تعذّر وصوله إلى الجوال." +
                                (relay?.stats()?.let { "\n(${status.state} · طلبات التلفاز: ${it.first} · رد المصدر: ${it.second})" } ?: "")
                            dialog = true
                            disconnect()
                        }
                    }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Some renderers omit status actions; transport commands still work. */ }
                }
            }
        }
    }

    fun togglePlay() {
        error = null
        val client = ownedSession?.remoteMediaClient
        if (client != null) {
            val result = if (remotePlaying) client.pause() else client.play()
            result.setResultCallback { if (!it.status.isSuccess) { error = "الجهاز لم يقبل أمر التشغيل أو الإيقاف."; dialog = true } }
        } else device?.let { target -> scope.launch {
            try { if (remotePlaying) dlna.pause(target) else dlna.play(target); remotePlaying = !remotePlaying }
            catch (_: Exception) { error = "الجهاز لم يقبل أمر التشغيل أو الإيقاف."; dialog = true }
        } }
    }
    fun seekBy(delta: Long) {
        if (live) return
        val at = (remotePosition + delta).coerceIn(0, (duration - 1000).coerceAtLeast(0))
        val client = ownedSession?.remoteMediaClient
        if (client != null) client.seek(at).setResultCallback {
            if (it.status.isSuccess) remotePosition = at else { error = "هذا الجهاز لم يقبل التقديم لهذا الفيديو."; dialog = true }
        } else device?.let { target -> scope.launch {
            try { dlna.seek(target, at); remotePosition = at }
            catch (_: Exception) { error = "هذا الجهاز لم يقبل التقديم لهذا الفيديو."; dialog = true }
        } }
    }

    fun disconnect(resume: Boolean = true) {
        epoch++
        job?.cancel(); job = null
        polling?.cancel(); polling = null
        pendingGoogle = false
        val session = ownedSession
        ownedSession = null
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        if (session != null) cast?.sessionManager?.endCurrentSession(true)
        val target = device
        device = null
        if (target != null) scope.launch(NonCancellable) { runCatching { dlna.stop(target) } }
        val wasConnected = connected
        deviceName = null
        if (wasConnected) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        remotePlaying = false
        busy = false
        stopRelay()
        if (wasConnected && !live && remotePosition > 0) player.seekTo(remotePosition)
        if (wasConnected && resume && wasPlaying && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.play()
    }
    private fun stopRelay() {
        relay?.onStopCasting = null
        relay = null
        binding?.let { runCatching { activity.unbindService(it) } }
        binding = null
        activity.stopService(Intent(activity, CastRelayService::class.java))
    }
    fun close() {
        closed = true
        chooser?.dismiss(); chooser = null
        cast?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        disconnect(resume = false)
    }
    fun dismissDialog() {
        dialog = false
        if (busy && !connected) disconnect(resume = false)
    }
}
