package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alyoushw.watchhub.data.CloudSync
import com.alyoushw.watchhub.data.WatchEntry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun whenText(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("d/M/yyyy  HH:mm", Locale.ENGLISH).format(Date(ms))

/** Owner only: who is registered, and each person's watch history, in plain words. */
@Composable
fun AdminSection() {
    val cloud = appContainer().cloud
    var users by remember { mutableStateOf<List<CloudSync.AdminUser>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<CloudSync.AdminUser?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    androidx.activity.compose.BackHandler(enabled = open != null) { open = null }

    LaunchedEffect(reload) {
        error = null
        runCatching { cloud.adminUsers() }
            .onSuccess { users = it }
            .onFailure { error = "ما قدرنا نجيب المسجلين. تأكد أنك نشرت قواعد Firestore الجديدة. (${it.message?.take(80)})" }
    }

    val current = open
    if (current != null) {
        AdminUserPage(current)
        return
    }

    var reports by remember { mutableStateOf<List<CloudSync.Report>>(emptyList()) }
    var showReports by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { runCatching { cloud.adminReports() }.onSuccess { reports = it } }
    val openCount = reports.count { !it.done }
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilterChip(modifier = Modifier.focusFrame(), selected = !showReports, onClick = { showReports = false }, label = { Text("المسجلين") })
        androidx.compose.material3.FilterChip(modifier = Modifier.focusFrame(), selected = showReports, onClick = { showReports = true }, label = { Text("البلاغات" + if (openCount > 0) " ($openCount)" else "") })
    }
    if (showReports) {
        if (reports.isEmpty()) Text("ما فيه بلاغات")
        reports.forEach { r ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text((if (r.done) "✓ " else "● ") + (r.name?.takeIf { it.isNotBlank() } ?: r.email ?: r.uid.take(8)), fontWeight = FontWeight.Bold)
                Text(r.text.ifBlank { "(بدون نص)" })
                r.problem?.let { Text("آخر مشكلة تشغيل: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                Text("${whenText(r.at)} · ${r.device.orEmpty()} · نسخة ${r.version.orEmpty()}", fontSize = 11.sp)
                Row {
                    TextButton(modifier = Modifier.focusFrame(), onClick = {
                        scope.launch { runCatching { cloud.adminReportDone(r.id, !r.done) }; reload++ }
                    }) { Text(if (r.done) "أعد فتحه" else "تم") }
                    users?.firstOrNull { it.uid == r.uid }?.let { u ->
                        TextButton(modifier = Modifier.focusFrame(), onClick = { open = u }) { Text("افتح حسابه") }
                    }
                    TextButton(modifier = Modifier.focusFrame(), onClick = {
                        scope.launch { runCatching { cloud.adminReportDelete(r.id) }; reload++ }
                    }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        return
    }
    Text("المسجلين (يظهر الشخص هنا بعد ما يفتح نسخة التطبيق الجديدة)", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (users == null && error == null) Text("نحمّل…")
    users?.let { list ->
        Text("العدد: ${list.size}", fontWeight = FontWeight.Bold)
        list.forEach { u ->
            Column(
                Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer).clickable { open = u }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(u.name?.takeIf { it.isNotBlank() } ?: u.email ?: u.uid.take(8), fontWeight = FontWeight.Bold)
                if (u.email != null) Text(u.email, fontSize = 13.sp)
                Text("آخر دخول: ${whenText(u.lastSeen)}${u.device?.let { " · $it" }.orEmpty()}", fontSize = 12.sp)
            }
        }
        TextButton(modifier = Modifier.focusFrame(), onClick = { reload++ }) { Text("تحديث") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AdminUserPage(user: CloudSync.AdminUser) {
    val cloud = appContainer().cloud
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<WatchEntry>?>(null) }
    var profile by remember { mutableStateOf<CloudSync.AdminProfile?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var showPass by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(user.uid, reload) {
        runCatching { cloud.adminHistory(user.uid) }.onSuccess { history = it }.onFailure { error = "ما قدرنا نقرأ السجل (${it.message?.take(80)})" }
        runCatching { cloud.adminProfile(user.uid) }.onSuccess { profile = it }
    }

    Text(user.name?.takeIf { it.isNotBlank() } ?: user.email ?: user.uid.take(8), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    user.email?.let { Text(it) }
    Text("آخر دخول: ${whenText(user.lastSeen)}${user.device?.let { " · $it" }.orEmpty()}", fontSize = 13.sp)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    val tabs = listOf("نظرة عامة", "تابع المشاهدة", "السجل", "قائمته", "حساباته")
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEachIndexed { i, t ->
            androidx.compose.material3.FilterChip(modifier = Modifier.focusFrame(), selected = tab == i, onClick = { tab = i }, label = { Text(t) })
        }
    }

    val list = history
    val p = profile
    if (list == null || p == null) { if (error == null) Text("نحمّل بياناته…"); return }

    fun mins(ms: Long) = ms / 60_000
    fun fmt(m: Long) = if (m >= 60) "${m / 60} س ${m % 60} د" else "$m د"
    val kindNames = mapOf("live" to "بث مباشر", "movie" to "أفلام", "episode" to "مسلسلات", "anime" to "أنمي")

    @Composable
    fun card(content: @Composable () -> Unit) = Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }

    when (tab) {
        0 -> {
            card {
                Text("أجهزته: ${p.devices.joinToString("، ").ifBlank { "—" }}")
                Text("المسجّل في سجله: ${list.size} · في قائمته: ${p.list.size} · حسابات IPTV: ${p.accounts.size}")
                Text("شاهد هالشهر: ${fmt(mins(p.byKind.values.sum()))} · أنهى ${p.finished}")
            }
            if (p.byKind.isNotEmpty()) card {
                Text("حسب النوع", fontWeight = FontWeight.Bold)
                p.byKind.entries.sortedByDescending { it.value }.forEach { Text("${kindNames[it.key] ?: it.key}: ${fmt(mins(it.value))}") }
            }
            if (p.topTitles.isNotEmpty()) card {
                Text("الأكثر مشاهدة", fontWeight = FontWeight.Bold)
                p.topTitles.forEach { Text("${it.first}: ${fmt(mins(it.second))}") }
            }
        }
        1 -> {
            // «تابع المشاهدة» as he sees it: the newest unfinished item of each title.
            val cont = list.filter { !it.isLive && it.durationMs > 0 && it.positionMs * 100 / it.durationMs < 95 }
                .distinctBy { it.seriesId ?: it.tmdbId?.toString() ?: it.title }.take(20)
            if (cont.isEmpty()) Text("ما عنده شي يكمله")
            cont.forEach { e -> historyRow(e, scope, cloud, user.uid) { history = history?.filter { x -> x.key != e.key } } }
        }
        2 -> {
            Text("السجل: ${list.size}", fontWeight = FontWeight.Bold)
            if (list.isNotEmpty()) TextButton(modifier = Modifier.focusFrame(), onClick = { confirmAll = true }) {
                Text("مسح كل سجله", color = MaterialTheme.colorScheme.error)
            }
            list.forEach { e -> historyRow(e, scope, cloud, user.uid) { history = history?.filter { x -> x.key != e.key } } }
        }
        3 -> {
            if (p.list.isEmpty()) Text("قائمته فاضية")
            p.list.forEach { w ->
                card {
                    Text(w.title, fontWeight = FontWeight.Bold)
                    val st = when (w.status) {
                        com.alyoushw.watchhub.data.WatchStatus.PLANNED -> "يبي يشوفه"
                        com.alyoushw.watchhub.data.WatchStatus.WATCHING -> "يتابعه"
                        com.alyoushw.watchhub.data.WatchStatus.DONE -> "خلّصه"
                    }
                    Text("$st${w.progress?.let { " · موسم ${it.season} حلقة ${it.episode}" }.orEmpty()}", fontSize = 12.sp)
                }
            }
        }
        else -> {
            card {
                Text("كورة: ${p.koraUrl ?: "الافتراضي"}")
                if (p.vipUser != null) Text("VIP: ${p.vipUser} / ${if (showPass) p.vipPass.orEmpty() else "••••••"}")
            }
            p.accounts.forEach { a ->
                card {
                    Text(a.name.ifBlank { a.host }, fontWeight = FontWeight.Bold)
                    Text("${a.host} · ${a.username} / ${if (showPass) a.password else "••••••"}", fontSize = 12.sp)
                }
            }
            TextButton(modifier = Modifier.focusFrame(), onClick = { showPass = !showPass }) { Text(if (showPass) "أخفِ كلمات المرور" else "أظهر كلمات المرور") }
        }
    }

    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text("مسح كل السجل؟") },
        text = { Text("بيتمسح سجل هالشخص من حسابه وكل أجهزته. ما يرجع.") },
        confirmButton = {
            TextButton(onClick = {
                confirmAll = false
                val keys = history.orEmpty().map { it.key }
                scope.launch { runCatching { cloud.adminRemove(user.uid, keys) }; reload++ }
            }) { Text("امسح", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("إلغاء") } },
    )
}

@Composable
private fun historyRow(e: WatchEntry, scope: kotlinx.coroutines.CoroutineScope, cloud: CloudSync, uid: String, onGone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.title.ifBlank { e.key }, fontWeight = FontWeight.Bold, maxLines = 2)
            val kind = when {
                e.isLive -> "بث مباشر"
                e.isEpisode -> "حلقة"
                else -> "فيلم"
            }
            val pct = if (e.durationMs > 0) " · ${(e.positionMs * 100 / e.durationMs).coerceIn(0, 100)}%" else ""
            Text("$kind${e.subtitle?.let { " · $it" }.orEmpty()}$pct", fontSize = 12.sp)
            Text(whenText(e.updatedAt), fontSize = 11.sp)
        }
        TextButton(modifier = Modifier.focusFrame(), onClick = {
            scope.launch { runCatching { cloud.adminRemove(uid, listOf(e.key)) }; onGone() }
        }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
    }
}

/** «أرسل مشكلة أو ملاحظة»: goes to the owner, with the last playback failure attached. */
@Composable
fun ReportDialog(onClose: () -> Unit) {
    val cloud = appContainer().cloud
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val problem = remember { cloud.lastProblem() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("أرسل للمدير") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اكتب المشكلة أو اقتراحك.", fontSize = 13.sp)
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().tvTextField(),
                    keyboardOptions = tvKeyboard(), minLines = 3,
                )
                if (problem != null) Text("بنرسل معها: $problem", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (text.isNotBlank() || problem != null), onClick = {
                busy = true
                scope.launch {
                    runCatching { cloud.sendReport(text, withProblem = true) }
                        .onSuccess { android.widget.Toast.makeText(context, "وصلت رسالتك للمدير، شكراً", android.widget.Toast.LENGTH_LONG).show(); onClose() }
                        .onFailure { busy = false; android.widget.Toast.makeText(context, it.message ?: "ما انرسلت", android.widget.Toast.LENGTH_LONG).show() }
                }
            }) { Text("إرسال") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } },
    )
}
