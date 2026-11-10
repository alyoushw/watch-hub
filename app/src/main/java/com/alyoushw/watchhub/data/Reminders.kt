package com.alyoushw.watchhub.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.alyoushw.watchhub.MainActivity
import com.alyoushw.watchhub.R

/**
 * "ذكرني": a notification shortly before a programme on a live channel starts (a match, a show).
 * Tapping it opens the channel. Reminders are kept on the device until they ring.
 */
object Reminders {
    const val ACTION_PLAY_LIVE = "com.alyoushw.watchhub.PLAY_LIVE"
    const val EXTRA_ACCOUNT = "account"
    const val EXTRA_STREAM = "stream"
    const val EXTRA_CHANNEL = "channel"
    private const val CHANNEL = "reminders"
    private const val BEFORE_MS = 5 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    private fun key(accountId: String, streamId: String, start: Long) = "$accountId|$streamId|$start"

    fun isSet(context: Context, accountId: String, streamId: String, start: Long) =
        prefs(context).contains(key(accountId, streamId, start))

    /** Sets (true) or clears (false) a reminder; returns whether one is set now. */
    fun toggle(context: Context, accountId: String, streamId: String, channel: String, title: String, start: Long): Boolean {
        val k = key(accountId, streamId, start)
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context, k, accountId, streamId, channel, title, start)
        if (isSet(context, accountId, streamId, start)) {
            am.cancel(pending)
            prefs(context).edit().remove(k).apply()
            return false
        }
        val at = (start - BEFORE_MS).coerceAtLeast(System.currentTimeMillis() + 5_000)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        prefs(context).edit().putString(k, "$channel|$title").apply()
        // Drop the ones that already rang.
        val now = System.currentTimeMillis()
        prefs(context).edit().apply {
            prefs(context).all.keys.filter { (it.substringAfterLast('|').toLongOrNull() ?: 0) < now - 3_600_000 }.forEach(::remove)
        }.apply()
        return true
    }

    private fun alarmIntent(context: Context, k: String, accountId: String, streamId: String, channel: String, title: String, start: Long): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_ACCOUNT, accountId).putExtra(EXTRA_STREAM, streamId).putExtra(EXTRA_CHANNEL, channel)
            .putExtra("title", title).putExtra("start", start)
        return PendingIntent.getBroadcast(context, k.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun ring(context: Context, intent: Intent) {
        val account = intent.getStringExtra(EXTRA_ACCOUNT) ?: return
        val stream = intent.getStringExtra(EXTRA_STREAM) ?: return
        val channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        val start = intent.getLongExtra("start", 0)
        prefs(context).edit().remove(key(account, stream, start)).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "تذكير البرامج", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context,
            key(account, stream, start).hashCode(),
            Intent(context, MainActivity::class.java).setAction(ACTION_PLAY_LIVE)
                .putExtra(EXTRA_ACCOUNT, account).putExtra(EXTRA_STREAM, stream).putExtra(EXTRA_CHANNEL, channel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val minutes = ((start - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (minutes > 0) "«$title» يبدأ بعد $minutes دقايق" else "«$title» بدأ الحين")
            .setContentText("على $channel · اضغط للمشاهدة")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(key(account, stream, start).hashCode(), n) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.ring(context, intent)
}
