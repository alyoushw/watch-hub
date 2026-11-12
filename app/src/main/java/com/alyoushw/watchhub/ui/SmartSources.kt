package com.alyoushw.watchhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alyoushw.watchhub.data.*
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.QueueItem

@Composable
fun SmartWatchSources(info: DetailInfo) {
    val container = appContainer()
    val context = LocalContext.current
    val catalogue by container.settings.sourceCatalogue.collectAsStateWithLifecycle()
    val indexed by container.settings.torrentIndexEnabled.collectAsStateWithLifecycle()
    var season by remember(info.id) { mutableIntStateOf(info.seasonDetails.firstOrNull()?.number ?: 1) }
    var episode by remember(info.id, season) { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SourceSearch?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val query = SourceQuery(info.type, info.id, info.originalTitle.ifBlank { info.searchTitle },
        info.year?.toIntOrNull() ?: 0, season.takeIf { info.type == MediaType.TV }, episode.takeIf { info.type == MediaType.TV }, info.imdbId)
    LaunchedEffect(query, catalogue, indexed, retry) {
        result = null
        if (!query.valid) return@LaunchedEffect
        loading = true
        try { result = container.watchSources.search(query) }
        finally { loading = false }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("مصادر المشاهدة داخل التطبيق")
        if (info.type == MediaType.TV) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(info.seasonDetails, key = { it.number }) { item ->
                    FilterChip(selected = season == item.number, onClick = { season = item.number },
                        label = { Text("الموسم ${item.number}") }, modifier = Modifier.focusFrame())
                }
            }
            val count = info.seasonDetails.firstOrNull { it.number == season }?.episodes ?: 0
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = episode > 1, onClick = { episode-- }, modifier = Modifier.focusFrame()) { Text("السابق") }
                Text("الحلقة $episode" + if (count > 0) " / $count" else "", modifier = Modifier.padding(vertical = 12.dp))
                OutlinedButton(enabled = episode < (count.takeIf { it > 0 } ?: 999), onClick = { episode++ }, modifier = Modifier.focusFrame()) { Text("التالي") }
            }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("جارٍ البحث عن مصدر مطابق…", style = MaterialTheme.typography.bodySmall)
        }
        if (!query.valid) Text("بيانات مطابقة العمل غير مكتملة.", style = MaterialTheme.typography.bodySmall)
        result?.let { found ->
            found.sources.forEach { source ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(source.providerName, color = Gold)
                        source.variants.forEach { variant ->
                            val torrent = variant.transport in setOf(SourceTransport.TORRENT, SourceTransport.MAGNET)
                            Button(onClick = {
                                val ref = WatchSourceRef(source.providerId, source.id, query, variant.id, source.providerName)
                                PlayerActivity.start(context, listOf(QueueItem(emptyList(), sourceEntry(info, ref))), 0)
                            }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                                Text((if (torrent) "تجهيز ملف التورنت" else "مشاهدة الآن") + " · ${variant.label}")
                            }
                        }
                        if (source.variants.any { it.transport in setOf(SourceTransport.TORRENT, SourceTransport.MAGNET) })
                            Text("يُحمَّل ملف الفيديو المختار كاملاً قبل التشغيل. الرجوع من المشغل يلغي التجهيز.", style = MaterialTheme.typography.bodySmall)
                        Text(source.attribution, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (found.sources.isEmpty()) {
                Text("لا يوجد مصدر متوافق حالياً لهذا العمل أو الحلقة.", style = MaterialTheme.typography.bodySmall)
            }
            found.errors.firstOrNull()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (found.sources.isEmpty() || found.errors.isNotEmpty()) TextButton(onClick = { retry++ }, modifier = Modifier.focusFrame()) { Text("إعادة البحث") }
        }
    }
}

internal fun sourceEntry(info: DetailInfo, ref: WatchSourceRef): WatchEntry = WatchEntry(
    key = "source:${ref.query.type.path}:${ref.query.tmdbId}:${ref.query.season ?: 0}:${ref.query.episode ?: 0}",
    kind = if (ref.query.type == MediaType.TV) WatchEntry.EPISODE else WatchEntry.MOVIE,
    itemId = ref.query.episode?.toString() ?: ref.query.tmdbId.toString(), title = info.title,
    subtitle = if (ref.query.type == MediaType.TV) "الموسم ${ref.query.season} · الحلقة ${ref.query.episode}" else info.year,
    image = (info.backdropPath ?: info.posterPath)?.let { "https://image.tmdb.org/t/p/w1280$it" },
    seriesId = ref.query.tmdbId.toString().takeIf { ref.query.type == MediaType.TV }, accountId = "watch-sources",
    tmdbId = info.id, sourceRef = ref,
)

@Composable
fun SourceSettingsSection() {
    val settings = appContainer().settings
    val saved by settings.sourceCatalogue.collectAsStateWithLifecycle()
    val indexed by settings.torrentIndexEnabled.collectAsStateWithLifecycle()
    var url by remember(saved) { mutableStateOf(saved) }
    var message by remember { mutableStateOf<String?>(null) }
    SectionTitle("مصادر المشاهدة")
    Text("تتوفر أمثلة أفلام Blender المفتوحة. لإضافة محتواك، أدخل رابط كتالوج مصادر يحدد العمل والمواسم والحلقات وحقوق الاستخدام.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(url, { url = it; message = null }, label = { Text("رابط كتالوج مصادرك") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard())
    Button(onClick = { settings.setSourceCatalogue(url); message = "حُفظ الكتالوج؛ أعد فتح صفحة العمل للبحث" }, modifier = Modifier.focusFrame()) { Text("حفظ المصادر") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    FilterChip(selected = indexed, onClick = { settings.setTorrentIndexEnabled(!indexed) },
        label = { Text("استخدام فهرس التورنت للمصادر المضافة") }, modifier = Modifier.focusFrame())
    Text("الفهرس يضيف معلومات للأعمال والتورنتات الموجودة في كتالوجك المصرّح به. روابط Magnet تُجهَّز بمحرك منفصل.", style = MaterialTheme.typography.bodySmall)
}
