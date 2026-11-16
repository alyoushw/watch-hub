package com.alyoushw.watchhub.player.casting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps the phone's session relay available while its screen is locked. */
class CastRelayService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@CastRelayService }
    private val binder = LocalBinder()
    private var relay: CastMediaRelay? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    internal var onStopCasting: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel("casting", "البث إلى التلفاز", NotificationManager.IMPORTANCE_LOW))
        startForeground(4207, notification("جاري تجهيز بث Watch Hub"))
        wake = requireNotNull(getSystemService(PowerManager::class.java)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchHub:Cast")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        wifi = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WatchHub:Cast")?.apply { setReferenceCounted(false); acquire() }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { onStopCasting?.invoke(); stopSelf() }
        return START_NOT_STICKY
    }
    internal suspend fun open(lan: CastLan, source: CastSource, flatHls: Boolean = false): String = withContext(Dispatchers.IO) {
        relay?.close()
        val server = CastMediaRelay(lan, source, flatHls)
        try {
            server.start(10_000, true)
            relay = server
            startForeground(4207, notification(source.title))
            server.mediaUrl
        } catch (e: Exception) { server.close(); throw e }
    }
    private fun notification(title: String) = NotificationCompat.Builder(this, "casting")
        .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Watch Hub · البث إلى التلفاز")
        .setContentText(title).setOngoing(true).setSilent(true)
        .addAction(0, "إيقاف البث", PendingIntent.getService(this, 4207,
            Intent(this, CastRelayService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    override fun onDestroy() {
        onStopCasting = null
        relay?.close(); relay = null
        wifi?.let { if (it.isHeld) it.release() }
        wake?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
