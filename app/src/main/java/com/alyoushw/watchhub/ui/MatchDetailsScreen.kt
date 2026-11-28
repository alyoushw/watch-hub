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
import com.alyoushw.watchhub.data.StandingRow
import com.alyoushw.watchhub.data.TChannel
import com.alyoushw.watchhub.data.TLineup
import com.alyoushw.watchhub.data.TifoApi
import com.alyoushw.watchhub.data.TifoMatch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * A match's page: score and venue, a button for the sources, and tabs for the statistics, the timeline, the line-ups,
 * and the form of both teams with their head-to-head. Everything but the score comes from the feed's match summary.
 */
@Composable
internal fun MatchDetailsDialog(m: LiveMatch, onSources: () -> Unit, onClub: (String, String, String, String?) -> Unit, onClose: () -> Unit) {
    var d by remember { mutableStateOf<MatchDetails?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var homeRow by remember { mutableStateOf<StandingRow?>(null) }
    var awayRow by remember { mutableStateOf<StandingRow?>(null) }
    var homeLast by remember { mutableStateOf<LiveMatch?>(null) }
    var awayLast by remember { mutableStateOf<LiveMatch?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // Both teams' place in their league tables and their last finished match (a cup match: their own league's).
    LaunchedEffect(m.id) {
        for ((id, isHome) in listOf(m.homeId to true, m.awayId to false)) {
            val slug = runCatching { ScoresApi.homeSlug(id, m.leagueSlug) }.getOrDefault(m.leagueSlug)
            val row = runCatching { ScoresApi.standings(slug) }.getOrDefault(emptyList()).firstOrNull { it.teamId == id }
            val last = runCatching { ScoresApi.teamMatches(slug, id) }.getOrDefault(emptyList()).filter { it.state == "post" && it.id != m.id }.maxByOrNull { it.startMs }
            if (isHome) { homeRow = row; homeLast = last } else { awayRow = row; awayLast = last }
        }
    }
    if (m.state == "pre") LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(m.id) {
        while (true) {
            d = runCatching { ScoresApi.details(m.leagueSlug, m.id, m.homeId, m.state == "post") }.getOrNull() ?: d
            loaded = true
            if (m.state == "post") break
            delay(45_000)
        }
    }
    // Tifo: the same match in the open feed (line-ups with pitch positions, events, statistics, channel and commentator).
    var tifo by remember { mutableStateOf<TifoMatch?>(null) }
    var chans by remember { mutableStateOf<List<TChannel>>(emptyList()) }
    var tifoChecked by remember { mutableStateOf(false) }
    LaunchedEffect(m.id) {
        val tid = runCatching { TifoApi.find(m) }.getOrNull()
        if (tid == null) { tifoChecked = true; return@LaunchedEffect }
        while (true) {
            tifo = runCatching { TifoApi.match(tid, m.state == "in") }.getOrNull() ?: tifo
            chans = runCatching { TifoApi.channels(tid) }.getOrDefault(chans)
            tifoChecked = true
            if (m.state == "post") break
            delay(if (m.state == "in") 30_000 else 120_000)
        }
    }
    // The cover takes the two clubs' colours.
    var homeColor by remember { mutableStateOf(Color(0xFF3A3028)) }
    var awayColor by remember { mutableStateOf(Color(0xFF28303A)) }
    LaunchedEffect(m.id) {
        suspend fun colorOf(id: String): Color? {
            val slug = runCatching { ScoresApi.homeSlug(id, m.leagueSlug) }.getOrDefault(m.leagueSlug)
            val hex = runCatching { ScoresApi.teams(slug) }.getOrDefault(emptyList()).firstOrNull { it.id == id }?.colorHex ?: return null
            return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
        }
        colorOf(m.homeId)?.let { homeColor = it }
        colorOf(m.awayId)?.let { awayColor = it }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                  // A cover made for each match: both clubs' colours, their crests big and faint, the competition and the time.
                  Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 232.dp).background(Brush.horizontalGradient(listOf(homeColor.copy(alpha = 0.9f), Color(0xFF0D0C0B), awayColor.copy(alpha = 0.9f))))) {
                    Box(Modifier.align(Alignment.CenterStart).alpha(0.14f)) { TeamLogo(m.homeLogo, m.home, 180.dp) }
                    Box(Modifier.align(Alignment.CenterEnd).alpha(0.14f)) { TeamLogo(m.awayLogo, m.away, 180.dp) }
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(modifier = Modifier.focusFrame(), onClick = onClose) { Text("‹ رجوع") }
                            ScoresApi.leagueLogos[m.leagueSlug]?.let { TeamLogo(it, "", 22.dp) }
                            Text(m.league, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(Modifier.width(110.dp).clickable { onClub(m.leagueSlug, m.homeId, m.home, m.homeLogo) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { TeamLogo(m.homeLogo, m.home, 64.dp); Text(m.home, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                when (m.state) {
                                    "in" -> Text("● ${m.clock}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                    "pre" -> {
                                        Text(matchWhen(m.startMs), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                        val left = (m.startMs - now).coerceAtLeast(0) / 1000
                                        Text(if (left >= 86400) "بعد ${left / 86400} يوم و ${(left % 86400) / 3600} ساعة" else "%02d:%02d:%02d".format(left / 3600, (left % 3600) / 60, left % 60), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    else -> Text("انتهت", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (m.state == "pre") "VS" else "${m.homeScore} - ${m.awayScore}", fontSize = 38.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.width(110.dp).clickable { onClub(m.leagueSlug, m.awayId, m.away, m.awayLogo) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { TeamLogo(m.awayLogo, m.away, 64.dp); Text(m.away, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                        }
                        d?.let { x ->
                            val info = listOfNotNull(x.venue, x.attendance?.let { "الحضور $it" }).joinToString(" · ")
                            if (info.isNotBlank()) Text(info, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                  }
                }
                item {
                    Button(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().focusFrame(), onClick = onSources) { Text("▶ شاهد · اختر المصدر") }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("التفاصيل", "الفريقان", "الإحصائيات", "الأحداث", "التشكيلة").forEachIndexed { i, t ->
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
                if (tab == 0) item { InfoPane(m, x, tifo, chans, tifoChecked) }
                else if (tab == 1) item { TeamsPane(m, x, homeRow, awayRow, homeLast, awayLast, onClub, tifo) }
                else if (tab == 2) item {
                    if (tifo?.stats?.isNotEmpty() == true) TifoStatsPane(tifo!!) else if (x != null) StatsPane(m, x)
                    else Text(if (loaded) "ما قدرنا نجيب الإحصائيات من المصدر." else "نجلب التفاصيل…", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else if (tab == 3) item {
                    if (tifo?.events?.isNotEmpty() == true) TifoEventsPane(m, tifo!!) else if (x != null) EventsPane(m, x)
                    else Text(if (loaded) "ما فيه أحداث بعد." else "نجلب التفاصيل…", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else item { LineupsTab(m, tifo, x) }
                item {
                    // Tifo's terms: a visible link to the source next to its numbers.
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Text(
                        "بيانات إضافية من " + TifoApi.SITE, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://" + TifoApi.SITE)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }.padding(8.dp),
                        textAlign = TextAlign.Center,
                    )
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
private fun InfoPane(m: LiveMatch, d: MatchDetails?, t: TifoMatch?, chans: List<TChannel>, checked: Boolean) {
    val date = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", java.util.Locale("ar")).format(java.time.Instant.ofEpochMilli(m.startMs).atZone(java.time.ZoneId.systemDefault()))
    Card2 {
        listOfNotNull(
            "البطولة" to m.league, t?.roundAr?.let { "الجولة" to it }, "الفريق المضيف" to m.home, "الفريق الضيف" to m.away, "التاريخ" to date,
            "الساعة" to clock(m.startMs), "الملعب" to (t?.venue ?: d?.venue ?: "—"),
            t?.referee?.let { "الحكم" to it }, (t?.attendance ?: d?.attendance?.toIntOrNull())?.let { "الحضور" to it.toString() },
        ).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 12.dp))
            }
        }
    }
    // The channels and commentators come from Tifo's TV table. Nothing is guessed: until they are announced it says so.
    Card2 {
        Text("القنوات الناقلة والمعلّقون", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (chans.isNotEmpty()) chans.distinct().forEach {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(it.channel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("🎙 " + it.commentator, fontSize = 14.sp)
            }
        } else Text(if (checked) "لم تُعلن القناة الناقلة والمعلّق بعد (تظهر هنا فور إعلانها)." else "نجلب…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Win / draw / loss chances (a rough statistical estimate from the tables and the last five matches, not an official forecast). */
private fun predict(h: StandingRow?, a: StandingRow?, hForm: List<String>, aForm: List<String>): Triple<Int, Int, Int>? {
    if (h == null || a == null || h.played == 0 || a.played == 0) return null
    fun strength(r: StandingRow, form: List<String>): Double {
        val ppg = r.points.toDouble() / r.played
        val gd = r.diff.toDouble() / r.played
        val f = if (form.isEmpty()) ppg else form.sumOf { g -> when (g.substringBefore(' ')) { "W" -> 3; "D" -> 1; else -> 0 }.toInt() }.toDouble() / form.size
        return 0.5 * ppg + 0.3 * f + 0.4 * gd
    }
    val diff = strength(h, hForm) + 0.25 - strength(a, aForm)
    val draw = (0.30 - 0.10 * kotlin.math.min(1.0, kotlin.math.abs(diff))).coerceIn(0.15, 0.32)
    val home = (1 - draw) / (1 + kotlin.math.exp(-1.6 * diff))
    val away = 1 - draw - home
    val ph = Math.round(home * 100).toInt(); val pd = Math.round(draw * 100).toInt()
    return Triple(ph, pd, 100 - ph - pd)
}

@Composable
private fun TeamsPane(m: LiveMatch, d: MatchDetails?, hr: StandingRow?, ar: StandingRow?, hLast: LiveMatch?, aLast: LiveMatch?, onClub: (String, String, String, String?) -> Unit, t: TifoMatch?) {
    fun pill(res: String): Pair<String, Color> = when (res) { "W" -> "ف" to Color(0xFF2F9E6B); "L" -> "خ" to Color(0xFFD04A4F); else -> "ت" to Color(0xFF7B7368) }
    val hForm = d?.form?.get(m.homeId).orEmpty(); val aForm = d?.form?.get(m.awayId).orEmpty()
    (t?.probs ?: predict(hr, ar, hForm, aForm))?.let { (ph, pd, pa) ->
        Card2 {
            Text(if (t?.probs != null) "احتمالات المباراة (نموذج تيفو)" else "احتمالات المباراة (تقدير)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            t?.expectedGoals?.let { Text("الأهداف المتوقعة: ${m.home} %.1f · ${m.away} %.1f".format(it.first, it.second), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                Box(Modifier.weight(ph.coerceAtLeast(1).toFloat()).fillMaxSize().background(MaterialTheme.colorScheme.primary))
                Box(Modifier.weight(pd.coerceAtLeast(1).toFloat()).fillMaxSize().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)))
                Box(Modifier.weight(pa.coerceAtLeast(1).toFloat()).fillMaxSize().background(Color(0xFF4A8CD0)))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${m.home} $ph%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("تعادل $pd%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${m.away} $pa%", fontWeight = FontWeight.Bold, color = Color(0xFF4A8CD0))
            }
            Text(if (t?.probs != null) "نموذج إحصائي من تيفو، مب توقع مضمون." else "تقدير إحصائي من ترتيب الفريقين وآخر ٥ مباريات، مب توقع رسمي.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (hr != null || ar != null) Card2 {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(m.home, Modifier.clickable { onClub(m.leagueSlug, m.homeId, m.home, m.homeLogo) }, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("إحصائيات الفريقين", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(m.away, Modifier.clickable { onClub(m.leagueSlug, m.awayId, m.away, m.awayLogo) }, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        fun pct(r: StandingRow?) = r?.let { if (it.played > 0) "${it.won * 100 / it.played}%" else "—" } ?: "—"
        listOf(
            "الترتيب" to (hr?.pos?.toString() ?: "—") to (ar?.pos?.toString() ?: "—"),
            "لعب" to (hr?.played?.toString() ?: "—") to (ar?.played?.toString() ?: "—"),
            "فاز" to (hr?.won?.toString() ?: "—") to (ar?.won?.toString() ?: "—"),
            "نسبة الفوز" to pct(hr) to pct(ar),
            "تعادل" to (hr?.drawn?.toString() ?: "—") to (ar?.drawn?.toString() ?: "—"),
            "خسر" to (hr?.lost?.toString() ?: "—") to (ar?.lost?.toString() ?: "—"),
            "الأهداف له" to (hr?.goalsFor?.toString() ?: "—") to (ar?.goalsFor?.toString() ?: "—"),
            "الأهداف عليه" to (hr?.goalsAgainst?.toString() ?: "—") to (ar?.goalsAgainst?.toString() ?: "—"),
            "الفارق" to (hr?.diff?.toString() ?: "—") to (ar?.diff?.toString() ?: "—"),
            "النقاط" to (hr?.points?.toString() ?: "—") to (ar?.points?.toString() ?: "—"),
        ).forEach { (kv, av) ->
            val (k, hv) = kv
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(hv, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(av, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
            }
        }
        fun lastText(g: LiveMatch?, id: String): String = g?.let {
            val mineHome = it.homeId == id
            val opp = if (mineHome) it.away else it.home
            val a = it.homeScore.toIntOrNull() ?: 0; val b = it.awayScore.toIntOrNull() ?: 0
            val res = if (a == b) "تعادل" else if ((a > b) == mineHome) "فاز" else "خسر"
            "$res على $opp ${it.homeScore}-${it.awayScore} (${java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale("ar")).format(java.time.Instant.ofEpochMilli(it.startMs).atZone(java.time.ZoneId.systemDefault()))})"
        } ?: "—"
        Text("آخر مباراة", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Text("${m.home}: ${lastText(hLast, m.homeId)}", fontSize = 13.sp)
        Text("${m.away}: ${lastText(aLast, m.awayId)}", fontSize = 13.sp)
    }
    listOf(m.homeId to m.home, m.awayId to m.away).forEach { (id, name) ->
        val games = d?.form?.get(id).orEmpty()
        if (games.isNotEmpty()) Card2 {
            Text("آخر مباريات $name", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                games.forEach { g -> val (t, c) = pill(g.substringBefore(' ')); Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(c), contentAlignment = Alignment.Center) { Text(t, color = Color.White, fontWeight = FontWeight.Bold) } }
            }
            games.forEach { g -> Text(g.substringAfter(' '), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (t != null && t.h2h.isNotEmpty() && (d == null || d.h2h.isEmpty())) Card2 {
        Text("المواجهات المباشرة", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        t.h2hSummary?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
        t.h2h.take(8).forEach { Text(it, fontSize = 13.sp) }
    }
    if (d != null && d.h2h.isNotEmpty()) Card2 {
        Text("المواجهات المباشرة", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        d.h2hSummary?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
        d.h2h.forEach { Text(it, fontSize = 13.sp) }
    }
    if (hr == null && ar == null && (d == null || (d.form.isEmpty() && d.h2h.isEmpty()))) Text("نجلب بيانات الفريقين…", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TifoStatsPane(t: TifoMatch) {
    Card2 {
        t.stats.forEach { s ->
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
private fun TifoEventsPane(m: LiveMatch, t: TifoMatch) {
    Card2 {
        t.events.forEach { e ->
            val icon = when {
                e.type.startsWith("هدف") -> "⚽"; e.type == "بطاقة صفراء" -> "🟨"; e.type == "بطاقة حمراء" -> "🟥"; e.type == "تبديل" -> "🔁"; else -> "•"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(e.minute, Modifier.width(48.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(icon, fontSize = 18.sp)
                Column(Modifier.weight(1f)) {
                    Text(e.player + (e.out?.let { " ⇄ $it" } ?: ""), fontSize = 14.sp)
                    Text(
                        e.type + (e.assist?.let { " · صناعة: $it" } ?: "") + (e.how?.let { " · $it" } ?: "") + (if (e.side == "home") " · ${m.home}" else if (e.side == "away") " · ${m.away}" else "") + (e.score?.let { " · $it" } ?: ""),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The line-ups: the official ones on a pitch drawn at the feed's positions; ESPN's list when Tifo has none; else the message. */
@Composable
private fun LineupsTab(m: LiveMatch, t: TifoMatch?, d: MatchDetails?) {
    val h = t?.home; val a = t?.away
    if (h != null && a != null) {
        PitchCard(m, h, a)
        listOf(m.home to h, m.away to a).forEach { (name, l) ->
            Card2 {
                Text("$name · المدرب: ${l.coach ?: "—"}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("الاحتياط", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                l.subs.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(p.number, Modifier.width(28.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Text(p.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.role, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (l.unavailable.isNotEmpty()) Text("غائبون: " + l.unavailable.joinToString("، "), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    } else if (d != null && d.lineups.isNotEmpty()) LineupsPane(d)
    else Card2 { Text("لم تصدر التشكيلة الرسمية بعد", fontWeight = FontWeight.Bold) ; Text("تظهر هنا تلقائياً فور صدورها، بدون ما تعيد فتح التطبيق.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun PitchCard(m: LiveMatch, h: TLineup, a: TLineup) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${m.away} · ${a.formation}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("${m.home} · ${h.formation}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E6B3A))) {
            val w = maxWidth; val hh = maxHeight
            Canvas(Modifier.fillMaxSize()) {
                val line = Color.White.copy(alpha = 0.55f); val st = Stroke(width = 2f)
                drawRect(line, style = st)
                drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
                drawCircle(line, radius = size.width * 0.14f, center = Offset(size.width / 2, size.height / 2), style = st)
                val bw = size.width * 0.5f; val bh = size.height * 0.14f
                drawRect(line, topLeft = Offset((size.width - bw) / 2, 0f), size = androidx.compose.ui.geometry.Size(bw, bh), style = st)
                drawRect(line, topLeft = Offset((size.width - bw) / 2, size.height - bh), size = androidx.compose.ui.geometry.Size(bw, bh), style = st)
            }
            // home: bottom half, own goal at the bottom; away: top half, mirrored
            @Composable fun place(l: TLineup, home: Boolean) = l.starters.forEach { p ->
                if (p.x < 0f || p.y < 0f) return@forEach
                val px = if (home) p.x else 1f - p.x
                val py = if (home) 1f - p.y / 2f else p.y / 2f
                val size = 40.dp
                Column(Modifier.offset(x = w * px - size / 2 - 0.dp, y = hh * py - size / 2).width(size + 24.dp).offset(x = (-12).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(size).clip(CircleShape).background(if (home) Color(0xFFE8A53A) else Color(0xFF4A8CD0)), contentAlignment = Alignment.Center) {
                        Text(p.number, fontWeight = FontWeight.Bold, color = Color(0xFF1C1206), fontSize = 14.sp)
                        AsyncImage(model = "https://images.fotmob.com/image_resources/playerimages/${p.id}.png", contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
                    }
                    Text(p.name.substringAfterLast(' '), color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
            place(h, true); place(a, false)
        }
    }
}
