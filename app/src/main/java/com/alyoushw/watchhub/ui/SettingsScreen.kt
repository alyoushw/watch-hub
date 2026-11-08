package com.alyoushw.watchhub.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import com.alyoushw.watchhub.data.XtreamAccount
import com.alyoushw.watchhub.data.XtreamApi
import com.alyoushw.watchhub.data.XtreamException
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
fun SettingsScreen(onOpenXtream: () -> Unit = {}) {
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
            AccountSection()

            HorizontalDivider()

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

            AppearanceSection()

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

            XtreamSection(onOpenXtream)

            HorizontalDivider()

            SectionTitle("عن التطبيق")
            Text(
                "Watch Hub ما يشغّل الفيديو بنفسه (إلا محتوى حساب Xtream Codes إذا أضفته) ولا يحفظ كلمات مرور حساباتك غير هذا الحساب. يجمع لك المحتوى في مكان واحد، " +
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

/** The user's Xtream Codes logins from their providers: a list, each with a name, and a form to add or edit one. */
@Composable
private fun XtreamSection(onOpen: () -> Unit) {
    val container = appContainer()
    val accounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    val active by container.settings.xtream.collectAsStateWithLifecycle()
    // null: no form open; an account with a blank id: adding a new one.
    var editing by remember { mutableStateOf<XtreamAccount?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("حسابات Xtream Codes")
        Text(
            "أضف حساب أو أكثر من مزوّدين رسميين، وسمّ كل واحد باسم تعرفه. القنوات والأفلام والمسلسلات تطلع في «قنواتي»، " +
                "وزر «مشاهدة» في صفحة أي عمل يبحث في كل حساباتك. البيانات محفوظة على جهازك بس.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        accounts.forEach { acc ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(acc.label, fontWeight = FontWeight.Bold)
                    Text(
                        acc.host.substringAfter("://") + if (acc.id == active.id && accounts.size > 1) "  ·  المعروض الحين" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                TextButton(modifier = Modifier.focusFrame(), onClick = { editing = acc }) { Text("تعديل") }
            }
        }
        if (editing == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.focusFrame(), onClick = { editing = XtreamAccount("", "", "") }) {
                    Text(if (accounts.isEmpty()) "+ أضف حساب" else "+ أضف حساب ثاني")
                }
                if (accounts.isNotEmpty()) {
                    OutlinedButton(modifier = Modifier.focusFrame(), onClick = onOpen) { Text("افتح «قنواتي»") }
                }
            }
        }
        editing?.let { acc ->
            XtreamAccountForm(
                initial = acc,
                onDone = { editing = null },
            )
        }
    }
}

@Composable
private fun XtreamAccountForm(initial: XtreamAccount, onDone: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val isNew = initial.id.isBlank()
    var name by remember(initial) { mutableStateOf(initial.name) }
    var host by remember(initial) { mutableStateOf(initial.host) }
    var username by remember(initial) { mutableStateOf(initial.username) }
    var password by remember(initial) { mutableStateOf(initial.password) }
    var show by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val draft = initial.copy(name = name, host = host, username = username, password = password)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (isNew) "حساب جديد" else "تعديل «${initial.label}»", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("اسم الحساب (مثلاً: البيت، الرياضة)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host,
            onValueChange = {
                host = it
                result = null
            },
            label = { Text("الهوست (الرابط)") },
            placeholder = { Text("http://example.com:8080") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = {
                username = it
                result = null
            },
            label = { Text("اسم المستخدم") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                result = null
            },
            label = { Text("كلمة المرور") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(modifier = Modifier.focusFrame(), onClick = { show = !show }) { Text(if (show) "إخفاء" else "إظهار") } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.focusFrame(),
                enabled = !testing && draft.isComplete,
                onClick = {
                    testing = true
                    result = null
                    scope.launch {
                        val saved = container.settings.saveXtreamAccount(draft.copy(name = name.ifBlank { "حساب ${container.settings.xtreamAccounts.value.size + if (isNew) 1 else 0}" }))
                        result = try {
                            val info = container.xtream.check(saved)
                            buildString {
                                append("✓ تم الحفظ والحساب شغّال")
                                info.status?.let { append("\nالحالة: $it") }
                                info.expiresAt?.let { append("\nينتهي: ${XtreamApi.formatDate(it)}") }
                                info.maxConnections?.let { append("\nالاتصالات: ${info.activeConnections ?: "0"} من $it") }
                            }
                        } catch (e: XtreamException) {
                            "تم الحفظ، بس: ${e.message}"
                        }
                        testing = false
                    }
                },
            ) { Text(if (testing) "جاري التحقق…" else "احفظ وجرّب") }
            TextButton(modifier = Modifier.focusFrame(), onClick = onDone) { Text(if (result?.startsWith("✓") == true) "تم" else "إغلاق") }
            if (!isNew) {
                Spacer(Modifier.weight(1f))
                TextButton(modifier = Modifier.focusFrame(), onClick = { confirmDelete = true }) {
                    Text("احذف", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        result?.let {
            Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("تحذف «${initial.label}»؟") },
            text = { Text("بينشال الحساب من التطبيق. تقدر تضيفه مرة ثانية متى ما تبي.") },
            confirmButton = {
                TextButton(modifier = Modifier.focusFrame(), onClick = {
                    container.settings.removeXtreamAccount(initial.id)
                    confirmDelete = false
                    onDone()
                }) { Text("احذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { confirmDelete = false }) { Text("إلغاء") } },
        )
    }
}


/** Light or dark look, and the accent colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection() {
    val container = appContainer()
    val mode by container.settings.themeMode.collectAsStateWithLifecycle()
    val accent by container.settings.accent.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("المظهر")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("dark" to "🌙 داكن", "light" to "☀️ نهاري", "system" to "حسب الجهاز").forEach { (key, label) ->
                FilterChip(
                    modifier = Modifier.focusFrame(),
                    selected = mode == key,
                    onClick = { container.settings.setThemeMode(key) },
                    label = { Text(label) },
                )
            }
        }
        Text("اللون", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Accents.forEach { (key, label, color) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .focusFrame(CircleShape)
                            .clip(CircleShape)
                            .background(color)
                            .border(if (accent == key) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { container.settings.setAccent(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (accent == key) Text("✓", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}


/**
 * «حسابك»: sign in with Google so "قائمتي", "تابع المشاهدة" (with the exact spot) and the Xtream
 * accounts are the same on every device. Phones use Google's sign-in sheet; TVs show a code.
 */
@Composable
private fun AccountSection() {
    val container = appContainer()
    val cloud = container.cloud
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by cloud.state.collectAsStateWithLifecycle()
    val lastSync by cloud.lastSync.collectAsStateWithLifecycle()
    val webUrl by container.settings.webUrl.collectAsStateWithLifecycle()
    val tv = isTv()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showCode by remember { mutableStateOf(false) }
    var editUrl by remember(webUrl) { mutableStateOf(webUrl) }
    var showUrl by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("حسابك")
        when (val st = state) {
            is com.alyoushw.watchhub.data.CloudSync.State.SignedIn -> {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Gold), contentAlignment = Alignment.Center) {
                        if (st.photo != null) {
                            coil.compose.AsyncImage(model = st.photo, contentDescription = null, modifier = Modifier.fillMaxSize())
                        } else {
                            Text((st.name ?: st.email ?: "؟").take(1), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(st.name ?: st.email.orEmpty(), fontWeight = FontWeight.Bold)
                        st.email?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(
                            if (lastSync > 0) "✓ مزامن · آخر تحديث ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastSync))}" else "✓ متصل · نجهّز المزامنة…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    TextButton(modifier = Modifier.focusFrame(), onClick = { cloud.signOut() }) { Text("خروج") }
                }
                Text(
                    "قائمتي، وتابع المشاهدة (لين الثانية اللي وقفت عندها)، وحسابات Xtream تتحدّث على كل جهاز داخل بنفس الحساب.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            com.alyoushw.watchhub.data.CloudSync.State.SignedOut -> {
                Text(
                    "سجّل دخولك بحساب Google، وكل أجهزتك (الجوال والتلفاز والموقع) تصير على نفس القائمة ونفس «تابع المشاهدة». " +
                        "توقف حلقة في الجوال، وتكملها في التلفاز من نفس الثانية.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!tv) {
                        Button(
                            modifier = Modifier.focusFrame(),
                            enabled = !busy,
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    message = try {
                                        cloud.signInWithGoogle(context as android.app.Activity)
                                        null
                                    } catch (e: com.alyoushw.watchhub.data.CloudException) {
                                        e.message
                                    }
                                    busy = false
                                }
                            },
                        ) { Text(if (busy) "جاري الدخول…" else "سجّل دخول بـ Google") }
                    }
                    if (cloud.config?.deviceLogin == true || tv) {
                        if (tv) {
                            Button(modifier = Modifier.focusFrame(), onClick = { showCode = true }) { Text("سجّل دخول برمز") }
                        } else {
                            OutlinedButton(modifier = Modifier.focusFrame(), onClick = { showCode = true }) { Text("دخول برمز") }
                        }
                    }
                }
            }
            com.alyoushw.watchhub.data.CloudSync.State.NotConfigured -> {
                Text(
                    "الحساب يحتاج إعداد مرة وحدة في موقعك على Render (خطوات «حساب Watch Hub» في ملف README). " +
                        "بعدها يطلع لك هنا زر تسجيل الدخول.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    modifier = Modifier.focusFrame(),
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            message = if (cloud.refreshConfig()) "✓ وصلت الإعدادات" else "لسا ما وصلت إعدادات الحساب من موقعك ($webUrl)"
                            busy = false
                        }
                    },
                ) { Text(if (busy) "نتحقق…" else "تحقق من جديد") }
            }
        }
        message?.let { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error) }

        TextButton(modifier = Modifier.focusFrame(), onClick = { showUrl = !showUrl }) {
            Text("رابط موقعك: ${webUrl.removePrefix("https://")}", style = MaterialTheme.typography.labelSmall)
        }
        if (showUrl) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = editUrl,
                    onValueChange = { editUrl = it },
                    label = { Text("رابط موقع Watch Hub") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(modifier = Modifier.focusFrame(), onClick = {
                    container.settings.setWebUrl(editUrl)
                    showUrl = false
                    scope.launch { cloud.refreshConfig() }
                }) { Text("حفظ") }
            }
        }
    }

    if (showCode) DeviceLoginDialog(onDone = { ok ->
        showCode = false
        if (ok) android.widget.Toast.makeText(context, "✓ دخلت، ونزامن الحين", android.widget.Toast.LENGTH_SHORT).show()
    })
}

/** TV sign-in: a short code to enter at google.com/device on a phone (with a QR code to get there). */
@Composable
private fun DeviceLoginDialog(onDone: (Boolean) -> Unit) {
    val cloud = appContainer().cloud
    var code by remember { mutableStateOf<com.alyoushw.watchhub.data.DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        code = null
        try {
            val c = cloud.startDeviceLogin()
            code = c
            if (cloud.finishDeviceLogin(c)) onDone(true) else error = "انتهت صلاحية الرمز أو انرفض. جرّب رمز جديد."
        } catch (e: com.alyoushw.watchhub.data.CloudException) {
            error = e.message
        }
    }

    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("سجّل دخول برمز") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val c = code
                when {
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    c == null -> CircularProgressIndicator()
                    else -> {
                        Text("من جوالك أو كمبيوترك افتح:", style = MaterialTheme.typography.bodyMedium)
                        Text(c.verificationUrl.removePrefix("https://"), fontWeight = FontWeight.Bold, color = Gold)
                        QrCode(c.verificationUrl, Modifier.size(150.dp))
                        Text("واكتب هذا الرمز:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            c.userCode,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("ننتظر موافقتك…", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (error != null) TextButton(modifier = Modifier.focusFrame(), onClick = { attempt++ }) { Text("رمز جديد") }
        },
        dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { onDone(false) }) { Text("إلغاء") } },
    )
}

/** A QR code for [text] (zxing), drawn as an image. */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        runCatching {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 300, 300)
            val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until m.width) for (y in 0 until m.height) {
                bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }
    bitmap?.let {
        androidx.compose.foundation.Image(it, contentDescription = "رمز QR", modifier = modifier.clip(RoundedCornerShape(8.dp)))
    }
}
