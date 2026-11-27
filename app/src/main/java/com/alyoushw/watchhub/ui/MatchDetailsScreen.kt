package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alyoushw.watchhub.data.LiveMatch
import com.alyoushw.watchhub.data.MatchDetails
import com.alyoushw.watchhub.data.ScoresApi
import kotlinx.coroutines.delay

/**
 * A match's page: score and venue, a button for the sources, and tabs for the statistics, the timeline, the line-ups,
 * and the form of both teams with their head-to-head. Everything but the score comes from the feed's match summary.
 */
@Composable
internal fun MatchDetailsDialog(m: LiveMatch, onSources: () -> Unit, onClose: () -> Unit) {
    var d by remember { mutableStateOf<MatchDetails?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(m.id) {
        while (true) {
            d = runCatching { ScoresApi.details(m.leagueSlug, m.id, m.homeId, m.state == "post") }.getOrNull() ?: d
            loaded = true
            if (m.state == "post") break
            delay(45_000)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(modifier = Modifier.focusFrame(), onClick = onClose) { Text("‹ رجوع") }
                            ScoresApi.leagueLogos[m.leagueSlug]?.let { TeamLogo(it, "", 22.dp) }
                            Text(m.league, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { TeamLogo(m.homeLogo, m.home, 64.dp); Text(m.home, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                when (m.state) {
                                    "in" -> Text("● ${m.clock}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                    "pre" -> Text(whenText(m.startMs), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                    else -> Text("انتهت", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (m.state == "pre") "VS" else "${m.homeScore} - ${m.awayScore}", fontSize = 38.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { TeamLogo(m.awayLogo, m.away, 64.dp); Text(m.away, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                        }
                        d?.let { x ->
                            val info = listOfNotNull(x.venue, x.attendance?.let { "الحضور $it" }).joinToString(" · ")
                            if (info.isNotBlank()) Text(info, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                }
                item {
                    Button(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().focusFrame(), onClick = onSources) { Text("▶ شاهد · اختر المصدر") }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("الإحصائيات", "الأحداث", "التشكيلة", "الفريقان").forEachIndexed { i, t ->
                            Text(
                                t, fontSize = 14.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium,
                                color = if (tab == i) Color(0xFF1C1206) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.focusFrame(CircleShape).clip(CircleShape)
                                    .background(if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable { tab = i }.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                val x = d
                if (x == null) {
                    item { Text(if (loaded) "ما قدرنا نجيب تفاصيل المباراة من المصدر." else "نجلب التفاصيل…", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else when (tab) {
                    0 -> item { StatsPane(m, x) }
                    1 -> item { EventsPane(m, x) }
                    2 -> item { LineupsPane(x) }
                    else -> item { TeamsPane(m, x) }
                }
            }
        }
    }
}

@Composable
private fun Card2(content: @Composable () -> Unit) = Column(
    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) { content() }

@Composable
private fun StatsPane(m: LiveMatch, d: MatchDetails) {
    if (d.stats.isEmpty()) { Text("الإحصائيات تظهر بعد بداية المباراة.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    Card2 {
        d.stats.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.home, fontWeight = FontWeight.Bold)
                    Text(s.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.away, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val a = s.homeShare.coerceIn(0.02f, 0.98f)
                    Box(Modifier.weight(a).fillMaxSize().background(MaterialTheme.colorScheme.primary))
                    Box(Modifier.weight(1f - a).fillMaxSize().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)))
                }
            }
        }
    }
}

@Composable
private fun EventsPane(m: LiveMatch, d: MatchDetails) {
    if (d.events.isEmpty()) { Text("ما فيه أحداث بعد.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    Card2 {
        d.events.forEach { e ->
            val icon = when {
                e.kind.startsWith("هدف") || e.kind.startsWith("ركلة جزاء (هدف") -> "⚽"
                e.kind == "بطاقة صفراء" -> "🟨"
                e.kind == "بطاقة حمراء" -> "🟥"
                e.kind == "تبديل" -> "🔁"
                else -> "•"
            }
            val team = if (e.teamId == m.homeId) m.home else if (e.teamId == m.awayId) m.away else ""
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(e.minute, Modifier.width(44.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(icon, fontSize = 18.sp)
                Column(Modifier.weight(1f)) {
                    Text(e.player.ifBlank { e.kind } + (e.other?.let { " ⇄ $it" } ?: ""), fontSize = 14.sp)
                    Text(e.kind + if (team.isNotBlank()) " · $team" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LineupsPane(d: MatchDetails) {
    if (d.lineups.isEmpty()) { Text("التشكيلة تظهر قبل المباراة بقليل.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    d.lineups.forEach { l ->
        Card2 {
            Text(l.team + if (l.formation.isNotBlank()) " · ${l.formation}" else "", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
            l.players.filter { it.starter }.forEach { PlayerLine(it.number, it.name, it.position) }
            val bench = l.players.filter { !it.starter }
            if (bench.isNotEmpty()) {
                Text("الاحتياط", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                bench.forEach { PlayerLine(it.number, it.name, it.position) }
            }
        }
    }
}

@Composable
private fun PlayerLine(number: String, name: String, pos: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(number, Modifier.width(28.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pos, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TeamsPane(m: LiveMatch, d: MatchDetails) {
    fun pill(res: String): Pair<String, Color> = when (res) { "W" -> "ف" to Color(0xFF2F9E6B); "L" -> "خ" to Color(0xFFD04A4F); else -> "ت" to Color(0xFF7B7368) }
    listOf(m.homeId to m.home, m.awayId to m.away).forEach { (id, name) ->
        val games = d.form[id].orEmpty()
        if (games.isNotEmpty()) Card2 {
            Text("آخر مباريات $name", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                games.forEach { g -> val (t, c) = pill(g.substringBefore(' ')); Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(c), contentAlignment = Alignment.Center) { Text(t, color = Color.White, fontWeight = FontWeight.Bold) } }
            }
            games.forEach { g -> Text(g.substringAfter(' '), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (d.h2h.isNotEmpty()) Card2 {
        Text("المواجهات المباشرة", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        d.h2hSummary?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
        d.h2h.forEach { Text(it, fontSize = 13.sp) }
    }
    if (d.form.isEmpty() && d.h2h.isEmpty()) Text("ما فيه بيانات لهذين الفريقين من المصدر.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
