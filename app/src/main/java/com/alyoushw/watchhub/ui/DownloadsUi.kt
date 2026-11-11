package com.alyoushw.watchhub.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.Download
import com.alyoushw.watchhub.data.DownloadState
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.player.PlayerActivity
import kotlinx.coroutines.delay
import java.util.Locale

/** "1.4 GB", "350 MB". */
fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

/**
 * "⬇" in the home bar: shows only when there are downloads, with a ring of progress while one
 * is downloading. Opens the downloads page.
 */
@Composable
fun DownloadsButton() {
    val container = appContainer()
    val list by container.downloads.items.collectAsStateWithLifecycle()
    if (list.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(list) {
        while (true) {
            val states = list.map { container.downloads.state(it) }
            val active = states.filter { !it.done && !it.failed && !it.paused }
            progress = if (active.isEmpty()) null else active.sumOf { it.bytes }.toFloat() / active.sumOf { it.total }.coerceAtLeast(1)
            delay(1_500)
        }
    }
    Box(
        Modifier.size(44.dp).focusFrame(CircleShape).clip(CircleShape).clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        progress?.let { CircularProgressIndicator(progress = { it }, modifier = Modifier.size(38.dp), color = Gold, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = 0.2f)) }
        Text("⬇", color = if (progress != null) Gold else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    if (open) DownloadsPage(onClose = { open = false })
}

/** The downloads page: each film / episode with %, speed, size so far of the total and time left. */
@Composable
fun DownloadsPage(onClose: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val saved by container.downloads.items.collectAsStateWithLifecycle()
    val live by container.downloads.live.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(modifier = Modifier.focusFrame(), onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") }
                    Text("التنزيلات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (saved.isEmpty()) {
                    MessageBox("ما فيه تنزيلات", "اضغط مطولاً على أي فيلم، أو على حلقة في صفحة المسلسل، واختر «نزّلها».")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(saved, key = { it.key }) { d ->
                            // Running ones report live (with a steady speed); the rest are read every second.
                            val st = live[d.key] ?: remember(tick, d.key) { container.downloads.state(d) }
                            DownloadRow(d, st, container.downloads.canPause(d), onPause = { container.downloads.pause(d.key) }, onResume = { container.downloads.resume(d.key) }, onPlay = {
                                val e = container.downloads.entryOf(d)
                                val url = container.downloads.localUrl(d.key)
                                if (e != null && url != null) {
                                    onClose()
                                    PlayerActivity.start(context, listOf(url), e)
                                }
                            }, onDelete = { container.downloads.remove(d.key) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    d: Download,
    st: DownloadState,
    canPause: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val speed = st.speed
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(width = 96.dp, height = 60.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (d.image != null) AsyncImage(model = d.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            d.subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            when {
                st.failed -> Text(
                    if (canPause) "⚠️ وقف التنزيل (انقطع الاتصال). اضغط ▶ يكمل من وين وقف" else "⚠️ فشل التنزيل، احذفه ونزّله من جديد",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                st.done -> Text("✓ جاهز للمشاهدة بدون نت · ${sizeText(st.total.takeIf { it > 0 } ?: st.bytes)}", style = MaterialTheme.typography.labelSmall, color = Gold)
                else -> {
                    LinearProgressIndicator(
                        progress = { st.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Gold,
                    )
                    val left = if (speed > 0 && st.total > 0 && !st.paused) (st.total - st.bytes) / speed else null
                    Text(
                        listOfNotNull(
                            "${Math.round(st.progress * 100)}%",
                            if (st.total > 0) "${sizeText(st.bytes)} من ${sizeText(st.total)}" else sizeText(st.bytes),
                            when {
                                st.paused -> "⏸ متوقف"
                                st.waiting -> "بالانتظار…"
                                else -> "${sizeText(speed)}/ث"
                            },
                            left?.let { s -> if (s >= 3600) "باقي ${s / 3600} س ${s % 3600 / 60} د" else "باقي ${s / 60} د ${s % 60} ث" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (st.done) {
            TextButton(modifier = Modifier.focusFrame(), onClick = onPlay) { Text("▶ شغّل") }
        } else if (canPause) {
            // Pause now, continue later from the same point.
            if (st.paused || st.failed) {
                TextButton(modifier = Modifier.focusFrame(), onClick = onResume) { Text("▶ كمّل") }
            } else {
                TextButton(modifier = Modifier.focusFrame(), onClick = onPause) { Text("⏸ إيقاف") }
            }
        }
        TextButton(modifier = Modifier.focusFrame(), onClick = onDelete) { Text("🗑") }
    }
}

/**
 * Download: inside the app (watch it here without internet), or with ADM (its own window opens
 * and the file is ADM's).
 */
@Composable
fun DownloadChoiceDialog(entry: WatchEntry, url: String, onDone: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val name = listOfNotNull(entry.title, entry.subtitle?.substringBefore(" · ", entry.subtitle)).joinToString(" - ")
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("تنزيل «${entry.title}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.fillMaxWidth().focusFrame(), onClick = {
                    container.downloads.start(entry, url)
                    Toast.makeText(context, "📥 بدأ التنزيل، تشوف تفاصيله من السهم ⬇ فوق في الرئيسية", Toast.LENGTH_LONG).show()
                    onDone()
                }) { Text("📥 تحميل داخل التطبيق") }
                Text("تشوفه بعدين بدون نت من التطبيق نفسه، مع كل أدوات المشغل.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(modifier = Modifier.fillMaxWidth().focusFrame(), onClick = {
                    if (!container.downloads.sendToAdm(context, url, name)) {
                        Toast.makeText(context, "برنامج ADM مو مثبّت، نفتح لك صفحته في المتجر", Toast.LENGTH_LONG).show()
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.dv.adm")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    onDone()
                }) { Text("⚡ تحميل بـ ADM") }
                Text("يفتح لك نافذة ADM ويبدأ التحميل فيه (أسرع، يكمل لو انقطع). الملف يكون في ADM، تشغّله من هناك.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = onDone) { Text("إلغاء") } },
    )
}
