package com.alyoushw.watchhub.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alyoushw.watchhub.data.AppUpdate
import kotlinx.coroutines.launch

/**
 * "يوجد تحديث جديد": checks GitHub when the app opens (and every few hours while open); one
 * press downloads the new build and opens Android's installer.
 */
@Composable
fun UpdateNotice() {
    if (com.alyoushw.watchhub.BuildConfig.IS_PREVIEW) return
    val updater = appContainer().updater
    val scope = rememberCoroutineScope()
    val update by updater.available.collectAsStateWithLifecycle()
    val progress by updater.progress.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var canInstall by remember { mutableStateOf(updater.canInstall()) }
    var hidden by remember { mutableStateOf(false) }

    // Phones on Android 13+ ask once for notifications, for the background "update ready" note.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) runCatching { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    // Each time the app comes to the front (opened, or back from the background): look for a
    // new build, at most once every 10 minutes. Also back from "install unknown apps": look again.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                canInstall = updater.canInstall()
                scope.launch { updater.checkIfDue() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val u: AppUpdate = update ?: return
    if (hidden || updater.wasDismissed(u) && progress == null) return
    val downloading = progress != null

    AlertDialog(
        onDismissRequest = { if (!downloading) hidden = true },
        title = { Text("يوجد تحديث جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("النسخة ${u.build} جاهزة (عندك ${updater.currentBuild}).")
                if (!canInstall) {
                    Text(
                        "أول مرة بس: اسمح لـ Watch Hub يثبّت التحديثات (يفتح لك الإعدادات، فعّل «السماح من هذا المصدر» وارجع).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                progress?.let { p ->
                    Text("ننزّل التحديث… ${Math.round(p * 100)}%", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Gold,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.focusFrame(),
                enabled = !downloading,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1C1206)),
                onClick = {
                    error = null
                    if (!updater.canInstall()) {
                        updater.openInstallPermission()
                        return@Button
                    }
                    scope.launch {
                        try {
                            updater.downloadAndInstall(u)
                        } catch (e: Exception) {
                            error = e.message ?: "صار خطأ"
                        }
                    }
                },
            ) { Text(if (canInstall) "تحديث" else "اسمح بالتثبيت", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(modifier = Modifier.focusFrame(), onClick = { updater.dismiss(u) }) { Text("لاحقاً") }
            }
        },
    )
}
