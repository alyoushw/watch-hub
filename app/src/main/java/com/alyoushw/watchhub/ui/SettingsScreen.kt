package com.alyoushw.watchhub.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.alpha
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.ColumnScope
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

private enum class SettingsPage(val title: String) {
    ACCOUNT("حسابك"), IPTV("حسابات IPTV"), TV_ROW("متابعة المشاهدة على التلفزيون"), APPEARANCE("المظهر"),
    YOUTUBE("اشتراكاتي"), ABOUT("التحديثات وعن التطبيق"), CLAUDE("البحث الذكي بـ Claude"),
    KORA("رابط كوره"), APPS("تطبيقاتي الإضافية"), MATCHES("مبارياتي"), ADMIN("لوحة المدير"),
}

/**
 * A clean list: the signed-in Google account on top, then one line per area; each opens its own page
 * (Back returns to the list). The watch summary opens its screen directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenXtream: () -> Unit = {}, onOpenHistory: () -> Unit = {}, onOpenTitle: (com.alyoushw.watchhub.data.MediaType, Int) -> Unit = { _, _ -> }) {
    val container = appContainer()
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    // A tap on the «بلاغ جديد» notification opens the owner's page on its reports tab.
    var adminReportsFirst by remember { mutableStateOf(false) }
    val openMatchSettings by container.openMatchSettings.collectAsStateWithLifecycle()
    LaunchedEffect(openMatchSettings) {
        if (openMatchSettings) { container.openMatchSettings.value = false; page = SettingsPage.MATCHES }
    }
    val openAdmin by container.openAdmin.collectAsStateWithLifecycle()
    LaunchedEffect(openAdmin) {
        if (openAdmin) {
            container.openAdmin.value = false
            if (container.cloud.isAdmin) { adminReportsFirst = true; page = SettingsPage.ADMIN }
        }
    }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "الإعدادات") },
                navigationIcon = {
                    if (page != null) IconButton(modifier = Modifier.focusFrame(), onClick = { page = null }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        val settingsScroll = remember(page) { androidx.compose.foundation.ScrollState(0) }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .tvScrollAssist(settingsScroll)
                .verticalScroll(settingsScroll)
                .readableWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                null -> SettingsHome(onOpen = { page = it }, onOpenHistory = onOpenHistory)
                SettingsPage.ACCOUNT -> AccountSection()
                SettingsPage.MATCHES -> MatchesSettingsSection()
                SettingsPage.ADMIN -> AdminSection(startOnReports = adminReportsFirst, onOpenTitle = onOpenTitle)
                SettingsPage.IPTV -> {
                    XtreamSection(onOpenXtream)
                    ChannelFoldersSection()
                    HorizontalDivider()
                    LiveLookSection()
                }
                SettingsPage.TV_ROW -> TvRowSection()
                SettingsPage.APPEARANCE -> AppearanceSection()
                SettingsPage.YOUTUBE -> YouTubeSection()
                SettingsPage.ABOUT -> {
                    AppVersionRow()
                    Text(
                        "Watch Hub يشغّل حلقات Animeify ومحتوى حسابات IPTV بالمشغل المدمج ويجمع لك المحتوى في مكان واحد، " +
                            "ولما تضغط «شاهد» يفتح العمل في تطبيق المنصة الرسمي.\n\n" +
                            "This product uses the TMDB API but is not endorsed or certified by TMDB. " +
                            "Streaming availability data is provided by JustWatch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingsPage.CLAUDE -> ClaudeSection()
                SettingsPage.KORA -> KoraLinkSection()
                SettingsPage.APPS -> CustomAppsSection()
            }
        }
    }
}

@Composable
private fun SettingsHome(onOpen: (SettingsPage) -> Unit, onOpenHistory: () -> Unit) {
    val container = appContainer()
    val s = container.settings
    val accounts by s.xtreamAccounts.collectAsStateWithLifecycle()
    val mode by s.themeMode.collectAsStateWithLifecycle()
    val youtube by s.youtubeEnabled.collectAsStateWithLifecycle()
    val subs by s.subscriptionsTab.collectAsStateWithLifecycle()
    val claudeKey by s.claudeKey.collectAsStateWithLifecycle()
    val useClaude by s.useClaude.collectAsStateWithLifecycle()
    val kora by s.koraUrl.collectAsStateWithLifecycle()
    val apps by s.customApps.collectAsStateWithLifecycle()
    val watchNext by s.watchNext.collectAsStateWithLifecycle()
    val month = remember { container.stats.thisMonth().totalMs / 60_000 }

    AccountCard { onOpen(SettingsPage.ACCOUNT) }
    SettingsGroup {
        SettingsRow("📊", "ملخص المشاهدات", if (month >= 60) "${month / 60} س ${month % 60} د هالشهر" else "$month د هالشهر", onOpenHistory)
        SettingsRow("📡", "حسابات IPTV", "${accounts.count { it.isComplete }} حسابات · المجلدات وشكل المباشر") { onOpen(SettingsPage.IPTV) }
    }
    SettingsGroup {
        if (isTv()) SettingsRow("📺", "متابعة المشاهدة على التلفزيون", if (watchNext) "مفعّلة" else "مطفية") { onOpen(SettingsPage.TV_ROW) }
        SettingsRow("🎨", "المظهر", when (mode) { "light" -> "فاتح"; "dark" -> "داكن"; else -> "حسب الجهاز" }) { onOpen(SettingsPage.APPEARANCE) }
        SettingsRow("📅", "اشتراكاتي", if (subs) "ظاهر في الشريط" else "مخفي") { onOpen(SettingsPage.YOUTUBE) }
        SettingsRow("⬆️", "التحديثات وعن التطبيق", "النسخة ${com.alyoushw.watchhub.BuildConfig.VERSION_NAME}") { onOpen(SettingsPage.ABOUT) }
    }
    SettingsGroup {
        SettingsRow("✨", "البحث الذكي بـ Claude", if (claudeKey.isNotBlank() && useClaude) "مفعّل" else "مطفي") { onOpen(SettingsPage.CLAUDE) }
        SettingsRow("⚽", "رابط كوره", android.net.Uri.parse(kora.ifBlank { KORA_HOME }).host.orEmpty()) { onOpen(SettingsPage.KORA) }
        SettingsRow("🏟️", "مبارياتي", "الدوريات والأندية المفضلة") { onOpen(SettingsPage.MATCHES) }
        SettingsRow("🧩", "تطبيقاتي الإضافية", if (apps.isEmpty()) "ما فيه" else "${apps.size} تطبيقات") { onOpen(SettingsPage.APPS) }
    }
    val context = LocalContext.current
    val cloudState by container.cloud.state.collectAsStateWithLifecycle()
    if (cloudState is com.alyoushw.watchhub.data.CloudSync.State.SignedIn && container.cloud.isAdmin) {
        val open by container.cloud.openReports.collectAsStateWithLifecycle()
        SettingsGroup { SettingsRow("🛡️", "لوحة المدير", if (open > 0) "📨 $open بلاغ جديد" else "المسجلين وسجل مشاهدتهم") { onOpen(SettingsPage.ADMIN) } }
    }
    var reporting by remember { mutableStateOf(false) }
    if (reporting) ReportDialog { reporting = false }
    SettingsGroup {
        SettingsRow("📨", "أرسل مشكلة أو ملاحظة", "للمدير، ويوصله معها تفاصيل آخر مشكلة تشغيل") { reporting = true }
        SettingsRow("📤", "مشاركة التطبيق", "أرسل رابط التحميل لأصحابك") { shareApp(context) }
    }
}

/** The download link always serves the newest official release. */
private const val APP_DOWNLOAD = "https://github.com/alyoushw/watch-hub/releases/latest/download/WatchHub.apk"

private fun shareApp(context: android.content.Context) {
    val text = "جرّب Watch Hub: أفلام ومسلسلات وأنمي وقنوات في مكان واحد.\nحمّله من هنا: $APP_DOWNLOAD"
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    val tv = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    runCatching { if (tv) error("no share on TV"); context.startActivity(android.content.Intent.createChooser(send, "مشاركة Watch Hub")) }.onFailure {
        // TVs often have nothing to share with: copy the link instead.
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Watch Hub", APP_DOWNLOAD))
        android.widget.Toast.makeText(context, "نسخنا رابط التحميل: $APP_DOWNLOAD", android.widget.Toast.LENGTH_LONG).show()
    }
}

/** The Google account this device is signed in with (or an invitation to sign in). */
@Composable
private fun AccountCard(onClick: () -> Unit) {
    val state by appContainer().cloud.state.collectAsStateWithLifecycle()
    val signed = state as? com.alyoushw.watchhub.data.CloudSync.State.SignedIn
    Row(
        Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Gold), contentAlignment = Alignment.Center) {
            if (signed?.photo != null) {
                coil.compose.AsyncImage(model = signed.photo, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text((signed?.name ?: signed?.email ?: "G").take(1), fontWeight = FontWeight.Bold, color = Color(0xFF1C1206), fontSize = 20.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(signed?.name ?: if (signed != null) "حسابك" else "سجّل دخولك بحساب Google", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                signed?.email ?: "عشان تتزامن المفضلة وتابع المشاهدة بين أجهزتك",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Text("‹", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

@Composable
private fun SettingsRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 17.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text("‹", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
    }
}

/** TV only: Watch Hub's row on the TV home screen. */
@Composable
private fun TvRowSection() {
    val container = appContainer()
    val watchNext by container.settings.watchNext.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("عرض أعمالي على شاشة التلفزيون الرئيسية", Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = watchNext, onCheckedChange = container.settings::setWatchNext, modifier = Modifier.focusFrame())
        }
        if (watchNext) {
            val homeContext = LocalContext.current
            OutlinedButton(onClick = { com.alyoushw.watchhub.tv.WatchNextPublisher.requestChannel(homeContext) }, modifier = Modifier.focusFrame()) {
                Text("إظهار صف Watch Hub")
            }
        }
        Text(
            "يعرض أعمال أشاهد حاليًا وما توقفت عنده؛ بطاقة المتابعة تستأنف المشغّل من موضع التوقف. ظهور الصفوف يعتمد على واجهة التلفزيون.",
            style = MaterialTheme.typography.bodySmall,
        )
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
                    modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(),
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

/** «كوره»'s address: three fixed choices — original, VIP, or a link you add yourself. */
@Composable
private fun KoraLinkSection() {
    val settings = appContainer().settings
    val saved by settings.koraUrl.collectAsStateWithLifecycle()
    // Which of the two built-in links is active now (blank counts as the original).
    val active = saved.ifBlank { KORA_HOME }
    val onOriginal = active == KORA_HOME
    val onVip = active == KORA_VIP
    val onCustom = !onOriginal && !onVip
    var editing by remember { mutableStateOf(false) }
    var text by remember(editing) { mutableStateOf(if (onCustom) active else "") }
    val vipUser by settings.koraVipUser.collectAsStateWithLifecycle()
    var vipEditing by remember { mutableStateOf(false) }
    var vUser by remember(vipEditing) { mutableStateOf(vipUser) }
    var vPass by remember(vipEditing) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(active, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KoraChoiceRow("الرابط الأصلي", KORA_HOME, selected = onOriginal) { settings.setKoraUrl(""); editing = false }
        KoraChoiceRow("رابط VIP", KORA_VIP, selected = onVip) { settings.setKoraUrl(KORA_VIP); editing = false }
        KoraChoiceRow("رابط أضيفه", if (onCustom) active else "اكتب رابطاً خاصاً بك", selected = onCustom) { editing = true }
        if (editing) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("الرابط الجديد") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.focusFrame(), onClick = { if (text.isNotBlank()) settings.setKoraUrl(text.trim()); editing = false }) { Text("حفظ") }
                TextButton(modifier = Modifier.focusFrame(), onClick = { editing = false }) { Text("إلغاء") }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("دخول VIP التلقائي", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (vipUser.isBlank())
                "أدخل بيانات اشتراك ماجد سبورت مرة وحدة هنا — تتزامن لكل أجهزتك ويسجّل الدخول تلقائياً، بدون كتابة بالريموت."
            else "محفوظ باسم: $vipUser — يسجّل تلقائياً على كل أجهزتك.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!vipEditing) {
            OutlinedButton(modifier = Modifier.focusFrame(), onClick = { vipEditing = true }) {
                Text(if (vipUser.isBlank()) "أضف بيانات VIP" else "تعديل بيانات VIP")
            }
        } else {
            OutlinedTextField(
                value = vUser, onValueChange = { vUser = it },
                label = { Text("اسم المستخدم") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(),
            )
            OutlinedTextField(
                value = vPass, onValueChange = { vPass = it },
                label = { Text("كلمة المرور" + if (vipUser.isNotBlank()) " (اتركها فاضية للإبقاء على الحالية)" else "") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.focusFrame(), onClick = {
                    if (vUser.isNotBlank()) settings.setKoraVip(vUser, vPass.ifBlank { settings.koraVipPass.value })
                    vipEditing = false
                }) { Text("حفظ") }
                TextButton(modifier = Modifier.focusFrame(), onClick = { vipEditing = false }) { Text("إلغاء") }
                if (vipUser.isNotBlank()) {
                    TextButton(modifier = Modifier.focusFrame(), onClick = { settings.setKoraVip("", ""); vipEditing = false }) { Text("حذف") }
                }
            }
        }
    }
}

@Composable
private fun KoraChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background((if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceVariant.copy(alpha = 0.5f)))
            .focusFrame(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (selected) "◉" else "◯", color = if (selected) scheme.primary else scheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Optional: the user's own Claude API key makes voice and text search understand anything. */
@Composable
private fun ClaudeSection() {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedKey by container.settings.claudeKey.collectAsStateWithLifecycle()
    val enabled by container.settings.useClaude.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (savedKey.isBlank()) {
            Text(
                "مع مفتاح Claude، البحث يفهم أي طلب بكلامك العادي.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(modifier = Modifier.focusFrame(), onClick = { showDialog = true }) { Text("+ أضف مفتاح Claude API") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Claude API", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                androidx.compose.material3.Switch(
                    checked = enabled,
                    onCheckedChange = { container.settings.setUseClaude(it) },
                    modifier = Modifier.focusFrame(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.focusFrame(), onClick = { showDialog = true }) { Text("تعديل المفتاح") }
                if (enabled) {
                    OutlinedButton(
                        modifier = Modifier.focusFrame(),
                        enabled = !testing,
                        onClick = {
                            testing = true
                            result = null
                            scope.launch {
                                result = container.assistant.test()
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "جاري التحقق…" else "جرّب") }
                }
            }
            result?.let {
                Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showDialog) {
        var key by remember { mutableStateOf(savedKey) }
        var show by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("مفتاح Claude API") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("من Claude Console (platform.claude.com).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(modifier = Modifier.focusFrame(), onClick = { show = !show }) { Text(if (show) "إخفاء" else "إظهار") } },
                        modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(),
                    )
                    if (isTv()) TextButton(modifier = Modifier.focusFrame(), onClick = { show = !show }) { Text(if (show) "إخفاء" else "إظهار") }
                    TextButton(modifier = Modifier.focusFrame(), onClick = { context.openUrl("https://platform.claude.com/settings/keys") }) { Text("افتح صفحة المفاتيح") }
                }
            },
            confirmButton = {
                TextButton(modifier = Modifier.focusFrame(), onClick = {
                    container.settings.setClaudeKey(key.trim())
                    if (key.isNotBlank()) container.settings.setUseClaude(true)
                    showDialog = false
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { showDialog = false }) { Text("إلغاء") } },
        )
    }
}

/** The user's Xtream Codes logins from their providers: a list, each with a name, and a form to add or edit one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun XtreamSection(onOpen: () -> Unit) {
    val container = appContainer()
    val accounts by container.settings.xtreamAccounts.collectAsStateWithLifecycle()
    val active by container.settings.xtream.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // null: no form open; an account with a blank id: adding a new one.
    var editing by remember { mutableStateOf<XtreamAccount?>(null) }
    var importOpen by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    if (importOpen) {
        val parsed = remember(importText) { XtreamAccount.parseList(importText) }
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text("لصق حسابات") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "الصق حساباتك ويُضاف الكل دفعة. تقدر تكتبها بأي طريقة:\n" +
                            "• سطر لكل حساب: الاسم | الهوست | المستخدم | المرور\n" +
                            "• أو كل خانة بسطر مع اسمها (بأي ترتيب)، وافصل بين الحسابات بسطر فاضي:\n" +
                            "   الاسم: سترونج\n   الهوست: http://...\n   المستخدم: ...\n   المرور: ...\n" +
                            "• أو رابط Xtream (get.php).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("الحسابات") },
                        modifier = Modifier.fillMaxWidth().tvTextField(),
                        minLines = 4,
                    )
                    Text(
                        if (importText.isBlank()) "" else "جاهز للإضافة: ${parsed.size} حساب",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.focusFrame(),
                    onClick = {
                        parsed.forEach { container.settings.saveXtreamAccount(it) }
                        importOpen = false
                        importText = ""
                        android.widget.Toast.makeText(context, "تمت إضافة ${parsed.size} حساب", android.widget.Toast.LENGTH_SHORT).show()
                    },
                ) { Text("أضف (${parsed.size})") }
            },
            dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { importOpen = false }) { Text("إلغاء") } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "أضف حساب أو أكثر من مزوّدين رسميين، وسمّ كل واحد باسم تعرفه. القنوات والأفلام والمسلسلات تطلع في «IPTV»، " +
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.focusFrame(), onClick = { editing = XtreamAccount("", "", "") }) {
                    Text(if (accounts.isEmpty()) "+ أضف حساب" else "+ أضف حساب ثاني")
                }
                OutlinedButton(modifier = Modifier.focusFrame(), onClick = { importOpen = true }) { Text("📋 لصق حسابات") }
                if (accounts.isNotEmpty()) {
                    OutlinedButton(modifier = Modifier.focusFrame(), onClick = onOpen) { Text("افتح «IPTV»") }
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
            modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(),
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
            modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = {
                username = it
                result = null
            },
            label = { Text("اسم المستخدم") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(),
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
            modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(),
        )
        // TV: the remote cannot reach a button inside the box; this one is below it.
        if (isTv()) TextButton(modifier = Modifier.focusFrame(), onClick = { show = !show }) { Text(if (show) "إخفاء" else "إظهار") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.focusFrame(),
                // Never disabled: a disabled button is skipped by the TV remote.
                enabled = !testing,
                onClick = {
                    if (!draft.isComplete) {
                        result = "كمّل البيانات: " + listOfNotNull(
                            "الهوست".takeIf { host.isBlank() },
                            "اسم المستخدم".takeIf { username.isBlank() },
                            "كلمة المرور".takeIf { password.isBlank() },
                        ).joinToString("، ")
                        return@Button
                    }
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
    // Which code dialog is open: "qr" (a signed-in phone scans it) or "code" (google.com/device).
    var loginDialog by remember { mutableStateOf<String?>(null) }
    var showEmail by remember { mutableStateOf(false) }
    var editUrl by remember(webUrl) { mutableStateOf(webUrl) }
    var showUrl by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    "المفضلة، وتابع المشاهدة (لين الثانية اللي وقفت عندها)، وحسابات Xtream تتحدّث على كل جهاز داخل بنفس الحساب.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            com.alyoushw.watchhub.data.CloudSync.State.SignedOut -> {
                GuestNote(onCleared = { message = "✓ انمسحت بيانات الزائر من هذا الجهاز" })
                Text(
                    "سجّل دخولك (بـ Google أو بالباركود أو بالإيميل وكلمة المرور)، وكل أجهزتك (الجوال والتلفاز والموقع) تصير على نفس القائمة ونفس «تابع المشاهدة». " +
                        "توقف حلقة في الجوال، وتكملها في التلفاز من نفس الثانية.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Every device offers the same ways in: Google, a QR code a signed-in phone approves, or email + password.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (cloud.config?.qrLogin == true) {
                        if (tv) Button(modifier = Modifier.focusFrame(), onClick = { loginDialog = "qr" }) { Text("سجّل دخول بالباركود") }
                        else OutlinedButton(modifier = Modifier.focusFrame(), onClick = { loginDialog = "qr" }) { Text("بالباركود") }
                    }
                    OutlinedButton(modifier = Modifier.focusFrame(), onClick = { showEmail = !showEmail }) { Text("بالإيميل وكلمة المرور") }
                    if (showEmail) EmailLoginForm(onDone = { showEmail = false })
                    if (cloud.config?.deviceLogin == true) {
                        OutlinedButton(modifier = Modifier.focusFrame(), onClick = { loginDialog = "code" }) { Text("دخول برمز (google.com/device)") }
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
                    modifier = Modifier.weight(1f).tvTextField(), keyboardOptions = tvKeyboard(),
                )
                Button(modifier = Modifier.focusFrame(), onClick = {
                    container.settings.setWebUrl(editUrl)
                    showUrl = false
                    scope.launch { cloud.refreshConfig() }
                }) { Text("حفظ") }
            }
        }
    }

    val done: (Boolean) -> Unit = { ok ->
        loginDialog = null
        if (ok) android.widget.Toast.makeText(context, "✓ دخلت، ونزامن الحين", android.widget.Toast.LENGTH_SHORT).show()
    }
    when (loginDialog) {
        // A QR code a signed-in phone (the app or the web) scans and approves.
        "qr" -> PairingDialog(onDone = done, onUseGoogleCode = { loginDialog = "code" })
        "code" -> DeviceLoginDialog(onDone = done)
    }
}

/** Email + password: sign in, create an account, or get a link to set a password (also for an account that only used Google). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmailLoginForm(onDone: () -> Unit) {
    val cloud = appContainer().cloud
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun run(okText: String? = null, block: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                block()
                if (okText != null) message = okText else onDone()
            } catch (e: com.alyoushw.watchhub.data.CloudException) {
                message = e.message
            }
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("الإيميل") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().tvTextField(),
            keyboardOptions = tvKeyboard(androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email)),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("كلمة المرور") },
            singleLine = true,
            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            // The eye shows the password so it can be checked while typing (no eye icon in the icon set we use).
            trailingIcon = {
                IconButton(modifier = Modifier.focusFrame(CircleShape), onClick = { showPassword = !showPassword }) {
                    Text("👁", fontSize = 20.sp, modifier = Modifier.alpha(if (showPassword) 1f else 0.45f))
                }
            },
            modifier = Modifier.fillMaxWidth().tvTextField(),
            keyboardOptions = tvKeyboard(androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password)),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(modifier = Modifier.focusFrame(), enabled = !busy, onClick = { run { cloud.signInWithEmail(email, password) } }) {
                Text(if (busy) "جاري…" else "دخول")
            }
            OutlinedButton(modifier = Modifier.focusFrame(), enabled = !busy, onClick = { run { cloud.signUpWithEmail(email, password) } }) { Text("حساب جديد") }
            TextButton(
                modifier = Modifier.focusFrame(),
                enabled = !busy,
                onClick = { run("✓ أرسلنا رابط كلمة المرور لإيميلك، افتحه وحدّد كلمة مرور") { cloud.sendPasswordReset(email) } },
            ) { Text("نسيت كلمة المرور") }
        }
        message?.let { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error) }
    }
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


/**
 * Shown while nobody is signed in: this device is in guest mode, with its own data. Lets the
 * owner wipe the guest's history, list and Xtream accounts (e.g. a shared TV).
 */
@Composable
private fun GuestNote(onCleared: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("👤", fontSize = 20.sp)
        Column(Modifier.weight(1f)) {
            Text("أنت الحين زائر", fontWeight = FontWeight.Bold)
            Text(
                "اللي تشوفه هنا خاص بهذا الجهاز. لما تسجّل دخول تطلع بياناتك أنت، ولما تطلع ترجع بيانات الزائر.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(modifier = Modifier.focusFrame(), onClick = { confirm = true }) {
            Text("امسحها", color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("تمسح بيانات الزائر؟") },
            text = { Text("ينمسح من هذا الجهاز: تابع المشاهدة، والمفضلة، وحسابات Xtream، والقنوات المفضلة. حسابك في Google ما يتأثر.") },
            confirmButton = {
                TextButton(modifier = Modifier.focusFrame(), onClick = {
                    confirm = false
                    scope.launch {
                        container.cloud.clearGuestData()
                        onCleared()
                    }
                }) { Text("امسح", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { confirm = false }) { Text("إلغاء") } },
        )
    }
}


/**
 * TV: "scan to sign in". A QR code that a phone signed in to Watch Hub opens (in the app, or the
 * web app) and approves with one tap; the TV then signs in to the same account by itself.
 */
@Composable
private fun PairingDialog(onDone: (Boolean) -> Unit, onUseGoogleCode: () -> Unit) {
    val cloud = appContainer().cloud
    var pairing by remember { mutableStateOf<com.alyoushw.watchhub.data.Pairing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        pairing = null
        try {
            val p = cloud.startPairing()
            pairing = p
            if (cloud.finishPairing(p)) onDone(true) else error = "انتهت صلاحية الرمز أو انرفض. اطلب رمز جديد."
        } catch (e: com.alyoushw.watchhub.data.CloudException) {
            error = e.message
        }
    }

    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("الدخول بالباركود") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val p = pairing
                when {
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    p == null -> CircularProgressIndicator()
                    else -> {
                        Text("صوّر الرمز بكاميرا جوال داخل حسابك:", style = MaterialTheme.typography.bodyMedium)
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.White).padding(10.dp)) {
                            QrCode(p.url, Modifier.size(210.dp))
                        }
                        Text(
                            "بيفتح Watch Hub في جوالك (لازم تكون داخل حسابك فيه)، اضغط «قبول» وبس.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            else if (cloud.config?.deviceLogin == true) TextButton(modifier = Modifier.focusFrame(), onClick = onUseGoogleCode) { Text("أو بـ google.com/device") }
        },
        dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { onDone(false) }) { Text("إلغاء") } },
    )
}

/**
 * Phone: a TV's QR code was opened here. Shows which device is asking, and lets it in to this
 * account with one tap (or refuses it). Shown over any screen (see Root).
 */
@Composable
fun PairApprovalDialog() {
    val cloud = appContainer().cloud
    val id by cloud.pendingPairing.collectAsStateWithLifecycle()
    val state by cloud.state.collectAsStateWithLifecycle()
    val pairingId = id ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var device by remember(pairingId) { mutableStateOf<String?>(null) }
    var loaded by remember(pairingId) { mutableStateOf(false) }
    var busy by remember(pairingId) { mutableStateOf(false) }
    var error by remember(pairingId) { mutableStateOf<String?>(null) }
    LaunchedEffect(pairingId) {
        device = cloud.pairingDevice(pairingId)
        loaded = true
    }
    val signedIn = state as? com.alyoushw.watchhub.data.CloudSync.State.SignedIn

    AlertDialog(
        onDismissRequest = { if (!busy) cloud.dismissPairing() },
        title = { Text("تسجيل دخول جهاز") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !loaded -> CircularProgressIndicator()
                    device == null -> Text("انتهت صلاحية الرمز. اطلب رمز جديد من التلفاز.")
                    signedIn == null -> Text("سجّل دخولك في هذا الجوال أول (الإعدادات ← حسابك)، وبعدها صوّر الرمز مرة ثانية.")
                    else -> {
                        Text("«${device}» يبي يدخل حسابك:", fontWeight = FontWeight.Bold)
                        Text(signedIn.email ?: signedIn.name.orEmpty(), color = Gold)
                        Text(
                            "بيشوف مفضلتك وتابع المشاهدة وحسابات Xtream. وافق بس إذا الجهاز قدامك.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (loaded && device != null && signedIn != null) {
                Button(
                    modifier = Modifier.focusFrame(),
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                cloud.answerPairing(pairingId, approve = true)
                                cloud.dismissPairing()
                                android.widget.Toast.makeText(context, "✓ وافقت، التلفاز يدخل الحين", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (e: com.alyoushw.watchhub.data.CloudException) {
                                error = e.message
                            }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "لحظة…" else "قبول") }
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.focusFrame(), enabled = !busy, onClick = {
                if (loaded && device != null && signedIn != null) scope.launch { runCatching { cloud.answerPairing(pairingId, approve = false) } }
                cloud.dismissPairing()
            }) { Text(if (loaded && device != null && signedIn != null) "رفض" else "إغلاق") }
        },
    )
}

/** "مجلدات القنوات": make, rename and delete the folders channels are sorted into. */
@Composable
private fun ChannelFoldersSection() {
    val settings = appContainer().settings
    val accounts by settings.xtreamAccounts.collectAsStateWithLifecycle()
    val folders by settings.xtreamFolders.collectAsStateWithLifecycle()
    if (accounts.isEmpty()) return
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("مجلدات القنوات")
        Text(
            "رتّب القنوات المباشرة في مجلداتك (رياضة، أخبار…). في صفحة المباشر اضغط مطولاً على أي قناة واختر مجلدها، والتطبيق يقترح لك المجلد المناسب.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        folders.forEach { (name, ids) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📁 $name", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text("${ids.size} قناة", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(modifier = Modifier.focusFrame(), onClick = { renaming = name }) { Text("تسمية") }
                TextButton(modifier = Modifier.focusFrame(), onClick = { settings.deleteFolder(name) }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it.take(30) },
                singleLine = true,
                placeholder = { Text("اسم مجلد جديد") },
                modifier = Modifier.weight(1f).tvTextField(), keyboardOptions = tvKeyboard(),
            )
            Button(
                modifier = Modifier.focusFrame(),
                enabled = newName.isNotBlank(),
                onClick = {
                    settings.createFolder(newName)
                    newName = ""
                },
            ) { Text("أضف") }
        }
    }
    renaming?.let { old ->
        var text by remember(old) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("اسم المجلد") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(30) }, singleLine = true, modifier = Modifier.tvTextField(), keyboardOptions = tvKeyboard()) },
            confirmButton = {
                TextButton(modifier = Modifier.focusFrame(), onClick = {
                    settings.renameFolder(old, text)
                    renaming = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { renaming = null }) { Text("إلغاء") } },
        )
    }
}

/**
 * "شكل المباشر": the new folder navigator (قسم ▸ مجموعة ▸ قناة ▸ جودة ▸ سيرفر), or the classic
 * channel list. The folder view gathers the same channel across every account under one entry.
 */
@Composable
private fun LiveLookSection() {
    val settings = appContainer().settings
    val on by settings.liveFolderUi.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("شكل المباشر")
        Text(
            "واجهة المجلدات: تدخل قسم (رياضة، أخبار…) ثم المجموعة ثم القناة، وتختار الجودة والسيرفر. " +
                "نفس القناة من كل حساباتك تتجمّع تحت مكان واحد. أطفئها لو تبي القائمة التقليدية.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("واجهة المجلدات", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            androidx.compose.material3.Switch(checked = on, onCheckedChange = settings::setLiveFolderUi, modifier = Modifier.focusFrame())
        }
    }
}

/**
 * "يوتيوب": shows the YouTube tab, which opens YouTube's own TV interface (signed in with the
 * user's account, so Premium applies) with Watch Hub's picture tools on the left.
 */
@Composable
private fun YouTubeSection() {
    val settings = appContainer().settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val subs by settings.subscriptionsTab.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("إظهار تبويب «اشتراكاتي»", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            androidx.compose.material3.Switch(checked = subs, onCheckedChange = settings::setSubscriptionsTab, modifier = Modifier.focusFrame())
        }
    }
}

/** This build's number, and a button to look for a newer one now. */
@Composable
private fun AppVersionRow() {
    if (com.alyoushw.watchhub.BuildConfig.IS_PREVIEW) {
        Text("نسخة خاصة · ${com.alyoushw.watchhub.BuildConfig.VERSION_NAME}")
        Text("هذه النسخة مستقلة عن التطبيق الرئيسي ولا تستقبل تحديثاته.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val updater = appContainer().updater
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("النسخة v${updater.currentVersion}", fontWeight = FontWeight.Medium)
            status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        OutlinedButton(modifier = Modifier.focusFrame(), onClick = {
            status = "نتحقق…"
            scope.launch {
                val u = updater.check(manual = true)
                status = if (u != null) "فيه نسخة جديدة: ${u.version?.let { "v$it" } ?: u.build}" else "عندك آخر نسخة ✓"
            }
        }) { Text("تحقق من التحديثات") }
    }
}

/** "📊 ملخص مشاهداتك": this month's watch time, what was finished, favourite genres. */
@Composable
private fun StatsSection(onOpenHistory: () -> Unit = {}) {
    val container = appContainer()
    val st = remember { container.stats.thisMonth() }
    val genres = container.recommender.topGenres
    fun hours(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} س ${m % 60} د" else "$m د"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("📊 ملخص مشاهداتك هالشهر")
        if (st.totalMs < 60_000) {
            Text("ما شفت شي هالشهر لين الحين (العدّ بدأ من هالنسخة).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("⏱ ${hours(st.totalMs)} مشاهدة", fontWeight = FontWeight.Bold, color = Gold)
            val parts = listOfNotNull(
                st.byKind["episode"]?.takeIf { it > 0 }?.let { "مسلسلات ${hours(it)}" },
                st.byKind["movie"]?.takeIf { it > 0 }?.let { "أفلام ${hours(it)}" },
                st.byKind["anime_episode"]?.takeIf { it > 0 }?.let { "أنمي ${hours(it)}" },
                st.byKind["live"]?.takeIf { it > 0 }?.let { "مباشر ${hours(it)}" },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text("✅ خلصت ${st.finished} حلقة وفيلم", style = MaterialTheme.typography.bodySmall)
            if (st.topTitles.isNotEmpty()) {
                Text("🏆 أكثر شي شفته: " + st.topTitles.joinToString("، ") { "${it.first} (${hours(it.second)})" }, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (genres.isNotEmpty()) Text("🎭 ذوقك: " + genres.joinToString("، "), style = MaterialTheme.typography.bodySmall)
        TextButton(modifier = Modifier.focusFrame(), onClick = onOpenHistory) { Text("📋 عرض ملخص التفاصيل") }
    }
}
