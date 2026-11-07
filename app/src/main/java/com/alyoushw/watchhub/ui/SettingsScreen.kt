package com.alyoushw.watchhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alyoushw.watchhub.data.TmdbException
import com.alyoushw.watchhub.data.openUrl
import kotlinx.coroutines.launch

private val Regions = listOf(
    "SA" to "السعودية",
    "AE" to "الإمارات",
    "KW" to "الكويت",
    "QA" to "قطر",
    "BH" to "البحرين",
    "OM" to "عُمان",
    "EG" to "مصر",
    "US" to "أمريكا",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedKey by container.settings.apiKey.collectAsStateWithLifecycle()
    val region by container.settings.region.collectAsStateWithLifecycle()

    var key by remember(savedKey) { mutableStateOf(savedKey) }
    var showKey by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("الإعدادات") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("مفتاح TMDB")
            Text(
                "TMDB قاعدة بيانات مجانية للأفلام والمسلسلات، ومنها نعرف وين متوفر كل عمل. سوّ حساب مجاني، " +
                    "وروح لصفحة الـ API وانسخ «API Key» أو «API Read Access Token» وحطه هنا.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { context.openUrl("https://www.themoviedb.org/settings/api") }) {
                Text("افتح صفحة مفاتيح TMDB")
            }
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    testMessage = null
                },
                label = { Text("المفتاح") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "إخفاء" else "إظهار") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !testing && key.isNotBlank(),
                    onClick = {
                        container.settings.setApiKey(key)
                        testing = true
                        testMessage = null
                        scope.launch {
                            testMessage = try {
                                container.api.search("Naruto")
                                "✓ تم الحفظ والمفتاح شغّال"
                            } catch (e: TmdbException) {
                                e.message
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "جاري التحقق…" else "احفظ وجرّب") }
            }
            testMessage?.let {
                Text(
                    it,
                    color = if (it.startsWith("✓")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()

            SectionTitle("منطقتك")
            Text(
                "التوفر يختلف من دولة لدولة، اختر الدولة اللي حسابك فيها.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Regions.forEach { (code, label) ->
                    FilterChip(
                        selected = region == code,
                        onClick = { container.settings.setRegion(code) },
                        label = { Text(label) },
                    )
                }
            }

            HorizontalDivider()

            SectionTitle("عن التطبيق")
            Text(
                "Watch Hub ما يشغّل الفيديو بنفسه ولا يحفظ كلمات مرور حساباتك. يجمع لك المحتوى في مكان واحد، " +
                    "ولما تضغط «شاهد» يفتح العمل في تطبيق المنصة الرسمي.\n\n" +
                    "كل بياناتك (قائمتك واشتراكاتك) محفوظة على جوالك بس.\n\n" +
                    "This product uses the TMDB API but is not endorsed or certified by TMDB. " +
                    "Streaming availability data is provided by JustWatch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
