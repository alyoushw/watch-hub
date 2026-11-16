package com.alyoushw.watchhub.player.casting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alyoushw.watchhub.player.PlayerIcons
import com.alyoushw.watchhub.ui.Gold

@Composable
internal fun PhoneCastButton(controller: PhoneCastController) {
    TextButton(onClick = { controller.dialog = true }, contentPadding = PaddingValues(4.dp)) {
        Icon(PlayerIcons.Cast, contentDescription = "البث إلى التلفاز", tint = if (controller.connected) Gold else Color.White)
        Spacer(Modifier.width(5.dp))
        Text("كروم كاست", color = if (controller.connected) Gold else Color.White)
    }
}

@Composable
internal fun PhoneCastPanel(controller: PhoneCastController) {
    BackHandler(enabled = controller.connected && !controller.dialog) { controller.disconnect() }
    // Remote transport controls replace local playback controls while the TV is playing.
    if (controller.connected) Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        PhoneCastButtonInCorner(controller)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(20.dp)) {
            Icon(PlayerIcons.Cast, null, Modifier.size(56.dp), tint = Gold)
            Text("${controller.protocol} · ${controller.deviceName}", color = Color.White, textAlign = TextAlign.Center)
            Text(if (controller.remotePlaying) "يتم التشغيل على جهاز البث" else "التحكم بالتشغيل على جهاز البث", color = Color.LightGray)
            if (!controller.live) Text(castTime(controller.remotePosition), color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!controller.live && controller.duration > 0) OutlinedButton(onClick = { controller.seekBy(-10_000) }) { Text("−10 ث") }
                Button(onClick = controller::togglePlay) { Text(if (controller.remotePlaying) "إيقاف مؤقت" else "تشغيل") }
                if (!controller.live && controller.duration > 0) OutlinedButton(onClick = { controller.seekBy(30_000) }) { Text("+30 ث") }
            }
            OutlinedButton(onClick = { controller.disconnect() }) { Text("إيقاف البث والعودة للجوال") }
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        }
    }
    if (controller.dialog) AlertDialog(onDismissRequest = controller::dismissDialog, title = { Text("البث إلى التلفاز") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (controller.connected) {
                Text("${controller.protocol} · ${controller.deviceName}")
                Button(onClick = controller::togglePlay, modifier = Modifier.fillMaxWidth()) { Text(if (controller.remotePlaying) "إيقاف مؤقت" else "تشغيل") }
                OutlinedButton(onClick = { controller.disconnect(); controller.dialog = false }, modifier = Modifier.fillMaxWidth()) { Text("إيقاف البث") }
            } else if (controller.dlnaPicker) {
                Text("DLNA · أجهزة تشغيل الفيديو على الشبكة")
                if (!controller.busy && controller.devices.isEmpty() && controller.error == null)
                    Text("لم يُعثر على أجهزة. تأكد من دعم التلفاز لـDLNA ومن اتصال الجهازين بشبكة Wi-Fi نفسها، دون عزل الأجهزة عن بعضها.")
                controller.devices.forEach { device ->
                    OutlinedButton(onClick = { controller.select(device) }, enabled = !controller.busy, modifier = Modifier.fillMaxWidth()) { Text(device.name) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = controller::discoverDlna, enabled = !controller.busy) { Text("إعادة البحث") }
                    TextButton(onClick = { controller.dlnaPicker = false }, enabled = !controller.busy) { Text("رجوع") }
                }
            } else {
                Text("اختر نوع الجهاز. يجب أن يكون الجوال والتلفاز على شبكة Wi-Fi نفسها.")
                OutlinedButton(onClick = controller::google, enabled = !controller.busy, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) { Text("Google Cast"); Text("Chromecast وأجهزة التلفاز الداعمة لـGoogle Cast", style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(onClick = controller::discoverDlna, enabled = !controller.busy, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) { Text("DLNA"); Text("التلفزيونات وأجهزة استقبال DLNA MediaRenderer", style = MaterialTheme.typography.bodySmall) }
                }
                Text("يعتمد التشغيل على دعم جهاز البث لصيغة الفيديو وترميزه. أبقِ الجوال متصلاً بالشبكة أثناء البث.", style = MaterialTheme.typography.bodySmall)
            }
            if (controller.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp)); Text("جاري الاتصال…")
            }
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = controller::dismissDialog) { Text("إغلاق") } })
}

@Composable
private fun BoxScope.PhoneCastButtonInCorner(controller: PhoneCastController) {
    Box(Modifier.align(AbsoluteAlignment.TopLeft).padding(12.dp)) { PhoneCastButton(controller) }
}

private fun castTime(ms: Long): String {
    val s = ms / 1000
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}
