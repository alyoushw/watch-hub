package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.CloudSync
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.TmdbApi
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.data.WatchStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun whenText(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("d/M/yyyy  HH:mm", Locale.ENGLISH).format(Date(ms))

/** The title page an entry belongs to, when it has one (live channels have none). */
internal fun entryTarget(e: WatchEntry): Pair<MediaType, Int>? {
    e.animeRef?.let { r -> MediaType.fromPath(r.detailType)?.let { return it to r.detailId } }
    val id = e.tmdbId ?: return null
    return (if (e.kind == WatchEntry.MOVIE) MediaType.MOVIE else MediaType.TV) to id
}

private fun kindLabel(e: WatchEntry) = when {
    e.isLive -> "بث مباشر"
    e.kind == WatchEntry.MOVIE -> "فيلم"
    e.kind == WatchEntry.ANIME -> "حلقة أنمي"
    else -> "حلقة"
}

private fun pct(e: WatchEntry) = if (e.durationMs > 0) (e.positionMs * 100 / e.durationMs).toInt().coerceIn(0, 100) else 0

@Composable
private fun AdminPoster(url: String?, w: Dp, h: Dp, round: Dp = 8.dp) {
    Box(Modifier.size(width = w, height = h).clip(RoundedCornerShape(round)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(h))
    }
}

@Composable
private fun Avatar(url: String?, size: Dp, fallback: String) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        else Text(fallback.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = (size.value / 2.4f).sp)
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Column(
    modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
) { content() }

@Composable
private fun Bar(percent: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(percent / 100f).height(4.dp).background(MaterialTheme.colorScheme.primary))
    }
}

/** Owner only: who is registered, and each person's account laid out like the app, tap a title to open it. */
@Composable
fun AdminSection(startOnReports: Boolean = false, onOpenTitle: (MediaType, Int) -> Unit = { _, _ -> }) {
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
        AdminUserPage(current, onOpenTitle)
        return
    }

    var reports by remember { mutableStateOf<List<CloudSync.Report>>(emptyList()) }
    var showReports by remember { mutableStateOf(startOnReports) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { runCatching { cloud.adminReports() }.onSuccess { reports = it } }
    val openCount = reports.count { !it.done }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(modifier = Modifier.focusFrame(), selected = !showReports, onClick = { showReports = false }, label = { Text("المسجلين") })
        FilterChip(modifier = Modifier.focusFrame(), selected = showReports, onClick = { showReports = true }, label = { Text("البلاغات" + if (openCount > 0) " ($openCount)" else "") })
    }
    if (showReports) {
        if (reports.isEmpty()) Text("ما فيه بلاغات")
        reports.forEach { r ->
            val who = users?.firstOrNull { it.uid == r.uid }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(who?.photo, 36.dp, r.name ?: r.email ?: "?")
                    Column {
                        Text((if (r.done) "✓ " else "● ") + (r.name?.takeIf { it.isNotBlank() } ?: r.email ?: r.uid.take(8)), fontWeight = FontWeight.Bold)
                        Text("${whenText(r.at)} · ${r.device.orEmpty()} · نسخة ${r.version.orEmpty()}", fontSize = 11.sp)
                    }
                }
                Text(r.text.ifBlank { "(بدون نص)" })
                r.place?.let { Text("📍 المكان: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                r.problem?.let { Text("آخر مشكلة تشغيل: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                Row {
                    TextButton(modifier = Modifier.focusFrame(), onClick = {
                        scope.launch { runCatching { cloud.adminReportDone(r.id, !r.done) }; reload++ }
                    }) { Text(if (r.done) "أعد فتحه" else "تم") }
                    MediaType.fromPath(r.targetType)?.let { t -> r.targetId?.let { id ->
                        TextButton(modifier = Modifier.focusFrame(), onClick = { onOpenTitle(t, id) }) { Text("افتح المكان") }
                    } }
                    if (who != null) TextButton(modifier = Modifier.focusFrame(), onClick = { open = who }) { Text("افتح حسابه") }
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
            Row(
                Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer).clickable { open = u }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(u.photo, 48.dp, u.name ?: u.email ?: "?")
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(u.name?.takeIf { it.isNotBlank() } ?: u.email ?: u.uid.take(8), fontWeight = FontWeight.Bold)
                    if (u.email != null) Text(u.email, fontSize = 13.sp)
                    Text("آخر دخول: ${whenText(u.lastSeen)}${u.device?.let { " · $it" }.orEmpty()}", fontSize = 12.sp)
                }
            }
        }
        TextButton(modifier = Modifier.focusFrame(), onClick = { reload++ }) { Text("تحديث") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AdminUserPage(user: CloudSync.AdminUser, onOpenTitle: (MediaType, Int) -> Unit) {
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

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Avatar(user.photo, 64.dp, user.name ?: user.email ?: "?")
        Column {
            Text(user.name?.takeIf { it.isNotBlank() } ?: user.email ?: user.uid.take(8), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            user.email?.let { Text(it, fontSize = 13.sp) }
            Text("آخر دخول: ${whenText(user.lastSeen)}${user.device?.let { " · $it" }.orEmpty()}", fontSize = 12.sp)
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    val tabs = listOf("نظرة عامة", "تابع المشاهدة", "السجل", "قائمته", "حساباته")
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEachIndexed { i, t ->
            FilterChip(modifier = Modifier.focusFrame(), selected = tab == i, onClick = { tab = i }, label = { Text(t) })
        }
    }

    val list = history
    val p = profile
    if (list == null || p == null) { if (error == null) Text("نحمّل بياناته…"); return }

    fun fmt(m: Long) = if (m >= 60) "${m / 60} س ${m % 60} د" else "$m د"
    val kindNames = mapOf("live" to "بث مباشر", "movie" to "أفلام", "episode" to "مسلسلات", "anime_episode" to "أنمي", "anime" to "أنمي")
    val open: (WatchEntry) -> Unit = { e -> entryTarget(e)?.let { (t, id) -> onOpenTitle(t, id) } }
    val remove: (WatchEntry) -> Unit = { e ->
        scope.launch { runCatching { cloud.adminRemove(user.uid, listOf(e.key)) }; history = history?.filter { x -> x.key != e.key } }
    }

    when (tab) {
        0 -> {
            Card {
                Text("أجهزته: ${p.devices.joinToString("، ").ifBlank { "—" }}")
                Text("سجله: ${list.size} · قائمته: ${p.list.size} · حسابات IPTV: ${p.accounts.size}")
                Text("شاهد هالشهر: ${fmt(p.byKind.values.sum() / 60_000)} · أنهى ${p.finished}")
            }
            val recent = list.filter { !it.isLive }.distinctBy { it.seriesId ?: it.tmdbId?.toString() ?: it.title }.take(12)
            if (recent.isNotEmpty()) {
                Text("آخر ما شاهده", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recent, key = { it.key }) { e ->
                        Column(
                            Modifier.width(110.dp).focusFrame(RoundedCornerShape(10.dp)).clickable(enabled = entryTarget(e) != null) { open(e) },
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AdminPoster(e.image, 110.dp, 160.dp)
                            if (e.durationMs > 0) Bar(pct(e))
                            Text(e.title, fontSize = 12.sp, maxLines = 2, fontWeight = FontWeight.SemiBold)
                            e.subtitle?.let { Text(it, fontSize = 10.sp, maxLines = 1) }
                        }
                    }
                }
            }
            if (p.byKind.isNotEmpty()) Card {
                Text("حسب النوع", fontWeight = FontWeight.Bold)
                val total = p.byKind.values.sum().coerceAtLeast(1)
                p.byKind.entries.sortedByDescending { it.value }.forEach {
                    Text("${kindNames[it.key] ?: it.key}: ${fmt(it.value / 60_000)}", fontSize = 13.sp)
                    Bar((it.value * 100 / total).toInt())
                }
            }
            if (p.topTitles.isNotEmpty()) Card {
                Text("الأكثر مشاهدة هالشهر", fontWeight = FontWeight.Bold)
                p.topTitles.forEach { Text("${it.first}: ${fmt(it.second / 60_000)}") }
            }
        }
        1 -> {
            val cont = list.filter { !it.isLive && it.durationMs > 0 && pct(it) < 95 }
                .distinctBy { it.seriesId ?: it.tmdbId?.toString() ?: it.title }.take(20)
            if (cont.isEmpty()) Text("ما عنده شي يكمله")
            cont.forEach { e -> EntryRow(e, big = true, onOpen = { open(e) }, onRemove = { remove(e) }) }
        }
        2 -> {
            Text("السجل: ${list.size} (اضغط على العنوان لفتح صفحته)", fontWeight = FontWeight.Bold)
            if (list.isNotEmpty()) TextButton(modifier = Modifier.focusFrame(), onClick = { confirmAll = true }) {
                Text("مسح كل سجله", color = MaterialTheme.colorScheme.error)
            }
            list.forEach { e -> EntryRow(e, big = false, onOpen = { open(e) }, onRemove = { remove(e) }) }
        }
        3 -> {
            if (p.list.isEmpty()) Text("قائمته فاضية")
            p.list.forEach { w ->
                Row(
                    Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).clickable { onOpenTitle(w.type, w.id) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AdminPoster(TmdbApi.image(w.posterPath, "w185"), 56.dp, 82.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(w.title, fontWeight = FontWeight.Bold, maxLines = 2)
                        val st = when (w.status) {
                            WatchStatus.PLANNED -> "يبي يشوفه"
                            WatchStatus.WATCHING -> "يتابعه"
                            WatchStatus.DONE -> "خلّصه"
                        }
                        Text("${w.type.label} · $st", fontSize = 12.sp)
                        w.progress?.let { Text("وصل: موسم ${it.season} حلقة ${it.episode}", fontSize = 12.sp) }
                    }
                }
            }
        }
        else -> {
            Card {
                Text("كورة: ${p.koraUrl ?: "الافتراضي"}")
                if (p.vipUser != null) Text("VIP: ${p.vipUser} / ${if (showPass) p.vipPass.orEmpty() else "••••••"}")
            }
            p.accounts.forEach { a ->
                Card {
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
private fun EntryRow(e: WatchEntry, big: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val canOpen = entryTarget(e) != null
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.weight(1f).focusFrame(RoundedCornerShape(10.dp)).clickable(enabled = canOpen, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AdminPoster(e.image, if (big) 70.dp else 52.dp, if (big) 100.dp else 76.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(e.title.ifBlank { e.key }, fontWeight = FontWeight.Bold, maxLines = 2)
                Text("${kindLabel(e)}${e.subtitle?.let { " · $it" }.orEmpty()}${if (e.durationMs > 0) " · ${pct(e)}%" else ""}", fontSize = 12.sp)
                if (e.durationMs > 0) Bar(pct(e))
                Text(whenText(e.updatedAt) + if (canOpen) "" else " · بدون صفحة", fontSize = 11.sp)
            }
        }
        TextButton(modifier = Modifier.focusFrame(), onClick = onRemove) { Text("حذف", color = MaterialTheme.colorScheme.error) }
    }
}

/** Where a report comes from: shown to the owner, and used to open that title for him. */
data class ReportContext(val place: String, val targetType: String? = null, val targetId: Int? = null)

fun reportContextFor(e: WatchEntry, where: String = "تابع المشاهدة"): ReportContext {
    val t = entryTarget(e)
    val ep = e.subtitle?.let { " · $it" }.orEmpty()
    val src = when { e.animeRef != null -> " · أنمي فاي"; e.fourURef != null -> " · 4U"; e.sourceRef != null -> " · مصدر خارجي"; e.accountId != null -> " · حساب IPTV"; else -> "" }
    return ReportContext("$where: «${e.title}»$ep$src", t?.first?.path, t?.second)
}

/** «أرسل مشكلة أو ملاحظة»: goes to the owner, with the place it came from and the last playback failure attached. */
@Composable
fun ReportDialog(context0: ReportContext? = null, onClose: () -> Unit) {
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
                context0?.let { Text("📍 ${it.place}", fontSize = 12.sp) }
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
                    runCatching { cloud.sendReport(text, withProblem = true, place = context0?.place, targetType = context0?.targetType, targetId = context0?.targetId) }
                        .onSuccess { android.widget.Toast.makeText(context, "وصلت رسالتك للمدير، شكراً", android.widget.Toast.LENGTH_LONG).show(); onClose() }
                        .onFailure { busy = false; android.widget.Toast.makeText(context, it.message ?: "ما انرسلت", android.widget.Toast.LENGTH_LONG).show() }
                }
            }) { Text("إرسال") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } },
    )
}
