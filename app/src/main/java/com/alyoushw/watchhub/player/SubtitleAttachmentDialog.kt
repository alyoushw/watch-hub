package com.alyoushw.watchhub.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alyoushw.watchhub.ui.focusFrame
import com.alyoushw.watchhub.ui.tvTextField
import com.alyoushw.watchhub.ui.tvKeyboard

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleAttachmentDialog(onDismiss: () -> Unit, onUrl: (String, String) -> Unit, onFile: (Uri, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("und") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { onFile(uri, language); onDismiss() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("إضافة ترجمة SRT / WebVTT") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(url, { url = it }, label = { Text("رابط ملف الترجمة") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("und" to "تلقائي", "ar" to "العربية", "en" to "الإنجليزية", "ja" to "اليابانية").forEach { (code, title) ->
                    FilterChip(selected = code == language, onClick = { language = code }, label = { Text(title) }, modifier = Modifier.focusFrame())
                }
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                Text("اختيار ملف من الجهاز")
            }
        }
    }, confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { onUrl(url.trim(), language); onDismiss() }, modifier = Modifier.focusFrame()) { Text("تحميل الترجمة") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusFrame()) { Text("إلغاء") } })
}
