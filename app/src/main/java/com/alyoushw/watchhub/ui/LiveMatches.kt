package com.alyoushw.watchhub.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.LiveMatch
import com.alyoushw.watchhub.data.ScoresApi
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.data.XtreamItem
import com.alyoushw.watchhub.player.ChannelIndex
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.QueueItem
import com.alyoushw.watchhub.player.channelQualityOrder
import com.alyoushw.watchhub.player.matchKey
import com.alyoushw.watchhub.player.qualityTags
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The channel group last used for each league, so it comes first next time (kept on this device). */
private object MatchPrefs {
    fun get(c: Context, league: String): String? = c.getSharedPreferences("match_prefs", Context.MODE_PRIVATE).getString(league, null)
    fun set(c: Context, league: String, key: String) { c.getSharedPreferences("match_prefs", Context.MODE_PRIVATE).edit().putString(league, key).apply() }
}

/** «مباريات الآن»: today's matches; tap one to pick which of your sources plays it. */
@Composable
fun LiveMatchesRow() {
    var matches by remember { mutableStateOf<List<LiveMatch>>(emptyList()) }
    var open by remember { mutableStateOf<LiveMatch?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { ScoresApi.fetch() }.onSuccess { all ->
                val soon = System.currentTimeMillis() + 8 * 3600_000L
                matches = all.filter { it.state == "in" || (it.state == "pre" && it.startMs < soon) }.take(24)
            }
            delay(60_000)
        }
    }
    if (matches.isEmpty()) return
    open?.let { MatchSourcesDialog(it) { open = null } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("مباريات الآن", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(matches, key = { it.leagueSlug + it.id }) { m -> MatchCard(m) { open = m } }
        }
    }
}

@Composable
private fun MatchCard(m: LiveMatch, onClick: () -> Unit) {
    Column(
        Modifier.width(250.dp).focusFrame(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(m.league, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TeamCol(m.home, m.homeLogo)
            val score = if (m.state == "pre") "—" else "${m.homeScore} - ${m.awayScore}"
            Text(score, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TeamCol(m.away, m.awayLogo)
        }
        val status = when (m.state) {
            "in" -> "● ${m.clock}"
            "pre" -> SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(m.startMs))
            else -> "انتهت"
        }
        Text(status, fontSize = 13.sp, color = if (m.state == "in") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TeamCol(name: String, logo: String?) {
    Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(40.dp)) { if (logo != null) AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp)) }
        Text(name, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center)
    }
}

private data class SourceGroup(val key: String, val title: String, val sources: List<Source>)
private data class Source(val account: com.alyoushw.watchhub.data.XtreamAccount, val channel: XtreamItem)

/** Every source of every channel matching the search, gathered from all your accounts, grouped by channel. */
@Composable
private fun MatchSourcesDialog(m: LiveMatch, onClose: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val league = remember { ScoresApi.leagues.firstOrNull { it.slug == m.leagueSlug } }
    val keywords = remember { league?.keywords.orEmpty() }
    var query by remember { mutableStateOf(keywords.firstOrNull().orEmpty()) }
    var all by remember { mutableStateOf<List<Source>?>(null) }
    var openKey by remember { mutableStateOf<String?>(null) }
    val accounts = container.settings.xtreamAccounts.value.filter { it.isComplete }

    LaunchedEffect(Unit) {
        val out = mutableListOf<Source>()
        for (acc in accounts) {
            runCatching { ChannelIndex.channels(container.xtream, acc) }.getOrNull()?.forEach { out += Source(acc, it) }
        }
        all = out
        MatchPrefs.get(context, m.leagueSlug)?.let { openKey = it }
    }

    val groups = remember(all, query) {
        val q = query.trim().lowercase()
        val src = all ?: return@remember emptyList<SourceGroup>()
        if (q.isBlank()) emptyList() else src.filter { it.channel.name.lowercase().contains(q) || matchKey(it.channel.name).contains(matchKey(q)) }
            .groupBy { matchKey(it.channel.name) }
            .map { (k, v) -> SourceGroup(k, v.minByOrNull { channelQualityOrder(it.channel.name) }!!.channel.name, v.sortedBy { channelQualityOrder(it.channel.name) }) }
            .sortedBy { it.key }
    }
    val preferred = MatchPrefs.get(context, m.leagueSlug)
    val ordered = remember(groups, preferred) { groups.sortedByDescending { it.key == preferred } }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${m.home} - ${m.away}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.broadcasts.isNotEmpty()) Text("الناقلة حسب المصدر: ${m.broadcasts.joinToString("، ")}", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    keywords.forEach { k -> FilterChip(modifier = Modifier.focusFrame(), selected = query.equals(k, true), onClick = { query = k }, label = { Text(k) }) }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("ابحث عن القناة (beIN, SSC...)") },
                    modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(), singleLine = true)
                when {
                    accounts.isEmpty() -> Text("أضف حساب IPTV من الإعدادات أول.")
                    all == null -> Text("نجمع قنواتك…")
                    ordered.isEmpty() -> Text("ما لقينا قناة بهذا الاسم في حساباتك.")
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ordered, key = { it.key }) { g ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                                Text(
                                    "${g.title}  ·  ${g.sources.size} مصدر",
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(12.dp)).clickable { openKey = if (openKey == g.key) null else g.key }.padding(12.dp),
                                )
                                if (openKey == g.key) g.sources.forEachIndexed { i, s ->
                                    val q = qualityTags(s.channel.name).joinToString(" ") { it.uppercase() }
                                    Text(
                                        "${s.account.label} · ${s.channel.name}" + if (q.isNotBlank()) "  [$q]" else "",
                                        fontSize = 13.sp,
                                        modifier = Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(10.dp)).clickable {
                                            MatchPrefs.set(context, m.leagueSlug, g.key)
                                            val items = g.sources.map { QueueItem(container.xtream.playUrls(it.channel, it.account), WatchEntry.live(it.channel, it.account), it.channel.num, it.channel) }
                                            onClose()
                                            PlayerActivity.start(context, items, i)
                                        }.padding(horizontal = 16.dp, vertical = 9.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = onClose) { Text("إغلاق") } },
    )
}
