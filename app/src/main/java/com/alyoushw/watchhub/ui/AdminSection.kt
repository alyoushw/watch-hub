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

@Composable
private fun AdminUserPage(user: CloudSync.AdminUser) {
    val cloud = appContainer().cloud
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<WatchEntry>?>(null) }
    var minutes by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(user.uid, reload) {
        runCatching { cloud.adminHistory(user.uid) }.onSuccess { history = it }.onFailure { error = "ما قدرنا نقرأ السجل (${it.message?.take(80)})" }
        runCatching { cloud.adminMinutes(user.uid) }.onSuccess { minutes = it }
    }

    Text(user.name?.takeIf { it.isNotBlank() } ?: user.email ?: user.uid.take(8), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    user.email?.let { Text(it) }
    Text("آخر دخول: ${whenText(user.lastSeen)}${user.device?.let { " · $it" }.orEmpty()}", fontSize = 13.sp)
    minutes?.let { Text("شاهد هالشهر: ${if (it >= 60) "${it / 60} س ${it % 60} د" else "$it د"}") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    val list = history
    if (list == null) { if (error == null) Text("نحمّل السجل…"); return }
    Text("السجل: ${list.size}", fontWeight = FontWeight.Bold)
    if (list.isNotEmpty()) {
        TextButton(modifier = Modifier.focusFrame(), onClick = { confirmAll = true }) {
            Text("مسح كل سجله", color = MaterialTheme.colorScheme.error)
        }
    }
    list.forEach { e ->
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
                scope.launch {
                    runCatching { cloud.adminRemove(user.uid, listOf(e.key)) }
                    history = history?.filter { it.key != e.key }
                }
            }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
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
