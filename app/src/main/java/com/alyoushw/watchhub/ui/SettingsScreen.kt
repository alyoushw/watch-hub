package com.alyoushw.watchhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import com.alyoushw.watchhub.data.InstalledApp
import com.alyoushw.watchhub.data.appLabel
import com.alyoushw.watchhub.data.launchableApps
import com.alyoushw.watchhub.data.supportsDirectSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val watchNext by container.settings.watchNext.collectAsStateWithLifecycle()

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
            SectionTitle("متابعة المشاهدة على التلفزيون")
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(
                    checked = watchNext,
                    onCheckedChange = { container.settings.setWatchNext(it) },
                    modifier = Modifier.focusFrame(),
                )
                Text("عرض قائمتي في Watch Next")
            }
            Text("يعرض الأعمال التي أتابعها والحلقة التالية حسب التقدم المسجل هنا. لا يقرأ دقيقة التوقف من تطبيقات المنصات. التفعيل يتيح ظهور العناوين في لانشر التلفزيون، والتعطيل يحذف بطاقات Watch Hub.")
            SectionTitle("مفتاح TMDB")
            Text(
                "TMDB قاعدة بيانات مجانية للأفلام والمسلسلات، ومنها نعرف وين متوفر كل عمل. سوّ حساب مجاني، " +
                    "وروح لصفحة الـ API وانسخ «API Key» أو «API Read Access Token» وحطه هنا.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(modifier = Modifier.focusFrame(), onClick = { context.openUrl("https://www.themoviedb.org/settings/api") }) {
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
                    TextButton(modifier = Modifier.focusFrame(), onClick = { showKey = !showKey }) { Text(if (showKey) "إخفاء" else "إظهار") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    modifier = Modifier.focusFrame(),
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
                    modifier = Modifier.focusFrame(),
                        selected = region == code,
                        onClick = { container.settings.setRegion(code) },
                        label = { Text(label) },
                    )
                }
            }

            HorizontalDivider()

            ClaudeSection()

            HorizontalDivider()

            CustomAppsSection()

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

private const val MAX_CUSTOM_APPS = 5

/** Pick a few installed apps once; each one becomes a button on every title page. */
@Composable
private fun CustomAppsSection() {
    val container = appContainer()
    val context = LocalContext.current
    val apps by container.settings.customApps.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("تطبيقاتي الإضافية")
        Text(
            "اختار التطبيقات اللي تبيها مرة وحدة، وبيطلع لك زر لكل واحد في صفحة أي فيلم أو مسلسل. " +
                "لما تضغطه ننسخ اسم العمل ونفتح التطبيق، وتلصق الاسم في البحث.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        apps.forEach { pkg ->
            val label = remember(pkg) { context.appLabel(pkg) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(pkg, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(label ?: "$pkg (مو مثبت)")
                    val direct = remember(pkg) { context.supportsDirectSearch(pkg) }
                    Text(
                        if (direct) "يفتح على البحث مباشرة" else "ينسخ الاسم ويفتح التطبيق",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(modifier = Modifier.focusFrame(), onClick = { container.settings.setCustomApps(apps - pkg) }) {
                    Text("شيل", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (apps.isNotEmpty()) {
            val seconds by container.settings.titleHintSeconds.collectAsStateWithLifecycle()
            Text(
                "إذا التطبيق ما يستقبل البحث مباشرة، نعرض لك اسم العمل على الشاشة عشان تكتبه. مدة العرض:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 20, 30).forEach { s ->
                    FilterChip(
                        modifier = Modifier.focusFrame(),
                        selected = seconds == s,
                        onClick = { container.settings.setTitleHintSeconds(s) },
                        label = { Text("$s ثانية") },
                    )
                }
            }
        }
        if (apps.size < MAX_CUSTOM_APPS) {
            OutlinedButton(modifier = Modifier.focusFrame(), onClick = { picking = true }) { Text("+ أضف تطبيق") }
        }
    }

    if (picking) {
        AppPickerDialog(
            exclude = apps.toSet(),
            onDismiss = { picking = false },
            onPick = { pkg ->
                container.settings.setCustomApps(apps + pkg)
                picking = false
            },
        )
    }
}

@Composable
private fun AppPickerDialog(exclude: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var all by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { context.launchableApps() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختار تطبيق") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("ابحث باسم التطبيق") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = all
                if (list == null) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val shown = list.filter { it.packageName !in exclude && it.label.contains(filter.trim(), ignoreCase = true) }
                    LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                        items(shown, key = { it.packageName }) { app ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(app.packageName) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AppIcon(app.packageName, size = 36.dp)
                                Text(app.label)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** Optional: the user's own Claude API key makes voice and text search understand anything. */
@Composable
private fun ClaudeSection() {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedKey by container.settings.claudeKey.collectAsStateWithLifecycle()
    val enabled by container.settings.useClaude.collectAsStateWithLifecycle()
    var key by remember(savedKey) { mutableStateOf(savedKey) }
    var show by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("البحث الذكي بـ Claude")
        Text(
            "مع مفتاح Claude، البحث بالصوت والكتابة يفهم أي طلب بكلامك العادي. " +
                "المفتاح من Claude Console (حساب API، غير اشتراك Claude العادي) والاستخدام يُحسب من رصيدك هناك. " +
                "إذا خلص الحد أو الرصيد، التطبيق يرجع للبحث العادي تلقائياً.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(modifier = Modifier.focusFrame(), onClick = { context.openUrl("https://platform.claude.com/settings/keys") }) {
            Text("افتح صفحة مفاتيح Claude")
        }
        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                result = null
            },
            label = { Text("مفتاح Claude API") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(modifier = Modifier.focusFrame(), onClick = { show = !show }) { Text(if (show) "إخفاء" else "إظهار") } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                    modifier = Modifier.focusFrame(),
                enabled = !testing && key.isNotBlank(),
                onClick = {
                    container.settings.setClaudeKey(key)
                    container.settings.setUseClaude(true)
                    testing = true
                    result = null
                    scope.launch {
                        result = container.assistant.test()
                        testing = false
                    }
                },
            ) { Text(if (testing) "جاري التحقق…" else "احفظ وجرّب") }
            if (savedKey.isNotBlank()) {
                FilterChip(
                    modifier = Modifier.focusFrame(),
                    selected = enabled,
                    onClick = { container.settings.setUseClaude(!enabled) },
                    label = { Text(if (enabled) "مفعّل" else "متوقف") },
                )
            }
        }
        result?.let {
            Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
        }
    }
}
