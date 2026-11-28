package com.alyoushw.watchhub.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alyoushw.watchhub.MainActivity
import com.alyoushw.watchhub.R
import com.alyoushw.watchhub.WatchHubApp
import com.alyoushw.watchhub.ui.clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Match reminders: a notification a quarter of an hour before kick-off and/or at kick-off, with a picture of the match
 * (the two crests on the clubs' colours). The owner chooses which matches (favourite clubs, whole favourite leagues, or both)
 * and the sound. The settings live on this device.
 */
object MatchReminders {
    private const val TAG = "match-remind"
    private fun prefs(c: Context) = c.getSharedPreferences("match_remind", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun before15(c: Context) = prefs(c).getBoolean("b15", true)
    fun atStart(c: Context) = prefs(c).getBoolean("b0", true)
    /** "teams" (favourite clubs), "leagues" (whole favourite leagues) or "both". */
    fun scope(c: Context) = prefs(c).getString("scope", "both").orEmpty()
    /** The chosen sound's address; empty = the phone's default notification sound. */
    fun sound(c: Context) = prefs(c).getString("sound", "").orEmpty()

    fun configure(c: Context, on: Boolean = enabled(c), b15: Boolean = before15(c), b0: Boolean = atStart(c), scope: String = scope(c), sound: String = sound(c)) {
        prefs(c).edit().putBoolean("on", on).putBoolean("b15", b15).putBoolean("b0", b0).putString("scope", scope).putString("sound", sound).apply()
        schedule(c)
    }

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!enabled(c)) { wm.cancelAllWorkByTag(TAG); wm.cancelUniqueWork("match-remind-scan"); return }
        wm.cancelAllWorkByTag(TAG) // the rules or the sound changed: what was queued is replaced by the next scan
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork("match-remind-scan", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<MatchRemindScanWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
        wm.enqueueUniqueWork("match-remind-scan-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<MatchRemindScanWorker>().setConstraints(net).build())
    }

    fun channelId(c: Context): String = "match_remind_" + sound(c).takeIf { it.isNotBlank() }.let { if (it == null) "default" else Integer.toHexString(it.hashCode()) }

    /** One channel per chosen sound (a channel's sound cannot be changed once it exists), the old ones are removed. */
    fun ensureChannel(c: Context): String {
        val nm = c.getSystemService(NotificationManager::class.java)
        val id = channelId(c)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.notificationChannels.filter { it.id.startsWith("match_remind_") && it.id != id }.forEach { nm.deleteNotificationChannel(it.id) }
            val ch = NotificationChannel(id, "تذكير المباريات", NotificationManager.IMPORTANCE_HIGH)
            val s = sound(c)
            if (s.isNotBlank()) ch.setSound(Uri.parse(s), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            nm.createNotificationChannel(ch)
        }
        return id
    }

    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    private suspend fun logo(url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { r -> r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } }.getOrNull()
    }

    /** The club's colour, read from its crest (the average of its coloured pixels). */
    private fun dominant(b: Bitmap?, fallback: Int): Int {
        if (b == null) return fallback
        val s = Bitmap.createScaledBitmap(b, 24, 24, true)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        val hsv = FloatArray(3)
        for (x in 0 until 24) for (y in 0 until 24) {
            val p = s.getPixel(x, y)
            if (Color.alpha(p) < 200) continue
            Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.2f || (hsv[1] < 0.18f && hsv[2] > 0.75f)) continue
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
        }
        return if (n == 0) fallback else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    /** The match picture: the two crests on their clubs' colours, the time between them (home on the right, like the match page). */
    suspend fun cover(home: String, away: String, homeLogo: String?, awayLogo: String?, league: String, startMs: Long): Bitmap {
        val hb = logo(homeLogo); val ab = logo(awayLogo)
        val w = 1000; val h = 500
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        fun dark(c: Int) = Color.rgb((Color.red(c) * 0.75).toInt(), (Color.green(c) * 0.75).toInt(), (Color.blue(c) * 0.75).toInt())
        val hc = dark(dominant(hb, Color.parseColor("#1b4fb0"))); val ac = dark(dominant(ab, Color.parseColor("#0a7a46")))
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(ac, Color.parseColor("#0D0C0B"), hc), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP) }
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        fun crest(b: Bitmap?, cx: Float) {
            if (b == null) return
            val size = 190
            val dst = Rect((cx - size / 2).toInt(), 120, (cx + size / 2).toInt(), 120 + size)
            cv.drawBitmap(b, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        crest(hb, w - 190f); crest(ab, 190f)
        fun label(t: String, cx: Float, y: Float, size: Float, color: Int = Color.WHITE, bold: Boolean = true, maxW: Float = 340f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; textAlign = Paint.Align.CENTER; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            while (p.measureText(t) > maxW && p.textSize > 22f) p.textSize -= 2f
            cv.drawText(t, cx, y, p)
        }
        label(home, w - 190f, 385f, 44f); label(away, 190f, 385f, 44f)
        label(league, w / 2f, 80f, 32f, Color.parseColor("#E8A53A"), maxW = 420f)
        label(clock(startMs), w / 2f, 200f, 46f, Color.parseColor("#D8D2C8"), bold = false)
        label("VS", w / 2f, 320f, 120f, maxW = 260f)
        return bmp
    }

    suspend fun notify(c: Context, id: String, home: String, away: String, homeLogo: String?, awayLogo: String?, league: String, startMs: Long, kind: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val chan = ensureChannel(c)
        val pic = runCatching { cover(home, away, homeLogo, awayLogo, league, startMs) }.getOrNull()
        val title = if (kind == 15) "⏰ $home × $away بعد ربع ساعة" else "⚽ بدأت الآن: $home × $away"
        val open = PendingIntent.getActivity(c, id.hashCode(), Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(c, chan)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText("$league · ${clock(startMs)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_EVENT).setContentIntent(open).setAutoCancel(true)
        if (pic != null) b.setLargeIcon(pic).setStyle(NotificationCompat.BigPictureStyle().bigPicture(pic).bigLargeIcon(null as Bitmap?))
        val s = sound(c)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && s.isNotBlank()) b.setSound(Uri.parse(s))
        runCatching { c.getSystemService(NotificationManager::class.java).notify(("mr$id$kind").hashCode(), b.build()) }
    }
}

/** Looks at the coming matches of your clubs/leagues and queues a reminder for each (runs every 15 minutes and when the settings change). */
class MatchRemindScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (!MatchReminders.enabled(c)) return Result.success()
        val settings = (c as? WatchHubApp)?.container?.settings ?: return Result.success()
        val favL = settings.matchLeagues.value; val favT = settings.matchTeams.value
        if (favL.isEmpty() && favT.isEmpty()) return Result.success()
        val matches = runCatching { ScoresApi.fetchMonths(2, ScoresApi.relevant(favL, favT)) }.getOrNull() ?: return Result.retry()
        val ids = favT.map { it.substringAfter(':') }.toSet()
        val scope = MatchReminders.scope(c)
        val now = System.currentTimeMillis()
        val wm = WorkManager.getInstance(c)
        for (m in matches) {
            if (m.state != "pre" || m.startMs < now - 60_000 || m.startMs > now + 30 * 3600_000L) continue
            val byTeam = m.homeId in ids || m.awayId in ids
            val byLeague = m.leagueSlug in favL
            if (!(when (scope) { "teams" -> byTeam; "leagues" -> byLeague; else -> byTeam || byLeague })) continue
            for ((kind, on) in listOf(15 to MatchReminders.before15(c), 0 to MatchReminders.atStart(c))) {
                if (!on) continue
                val delay = m.startMs - (if (kind == 15) 15 * 60_000L else 0L) - now
                if (delay < -2 * 60_000L) continue
                val data = Data.Builder().putString("id", m.id).putString("home", m.home).putString("away", m.away).putString("hl", m.homeLogo).putString("al", m.awayLogo)
                    .putString("league", m.league).putLong("start", m.startMs).putInt("kind", kind).build()
                wm.enqueueUniqueWork("mr-${m.id}-$kind", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MatchRemindWorker>().setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS).addTag("match-remind").setInputData(data).build())
            }
        }
        return Result.success()
    }
}

class MatchRemindWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val d = inputData
        if (!MatchReminders.enabled(applicationContext)) return Result.success()
        MatchReminders.notify(
            applicationContext, d.getString("id").orEmpty(), d.getString("home").orEmpty(), d.getString("away").orEmpty(),
            d.getString("hl"), d.getString("al"), d.getString("league").orEmpty(), d.getLong("start", 0), d.getInt("kind", 0),
        )
        return Result.success()
    }
}
