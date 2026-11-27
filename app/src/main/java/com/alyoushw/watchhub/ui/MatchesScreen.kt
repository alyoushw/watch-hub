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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.LiveMatch
import com.alyoushw.watchhub.data.ScoresApi
import com.alyoushw.watchhub.data.StandingRow
import com.alyoushw.watchhub.data.TeamInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/** The crest from the feed; a plain circle with the first letter when the feed has none. */
@Composable
internal fun TeamLogo(url: String?, name: String, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        } else {
            Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(name.take(1), fontWeight = FontWeight.Bold, fontSize = (size.value / 2.4f).sp)
            }
        }
    }
}

private fun localDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(ms))

/** "اليوم ٢٠:٠٠", "غداً ٢٠:٠٠", or the Gregorian date in words ("الأحد ٣٠ نوفمبر · ٢٠:٠٠"). */
private fun whenText(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(ms).atZone(zone)
    val today = LocalDate.now(zone)
    val time = clock(ms)
    return when (d.toLocalDate()) {
        today -> "اليوم $time"
        today.plusDays(1) -> "غداً $time"
        today.minusDays(1) -> "أمس $time"
        else -> java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("ar")).format(d) + " · $time"
    }
}

private fun dateText(ms: Long): String = java.time.format.DateTimeFormatter.ofPattern("d MMMM", Locale("ar")).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun upcoming(m: LiveMatch) = m.state != "post" && m.startMs > System.currentTimeMillis() - 4 * 3600_000L

/** What opens a club page. */
internal data class ClubRef(val slug: String, val id: String, val name: String, val logo: String?)

/**
 * The «مباريات» tab: your leagues' and clubs' matches by day (a calendar strip), the league tables, and your
 * clubs. Tap a match to choose which of your sources plays it; tap a club for its page.
 */
@Composable
fun MatchesScreen(onOpenMatchSettings: () -> Unit) {
    val container = appContainer()
    val favLeagues by container.settings.matchLeagues.collectAsStateWithLifecycle()
    val favTeams by container.settings.matchTeams.collectAsStateWithLifecycle()
    val favIds = remember(favTeams) { favTeams.map { it.substringAfter(':') }.toSet() }
    var all by remember { mutableStateOf<List<LiveMatch>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var sub by remember { mutableIntStateOf(0) }
    var dayOffset by remember { mutableIntStateOf(0) }
    var openMatch by remember { mutableStateOf<LiveMatch?>(null) }
    var club by remember { mutableStateOf<ClubRef?>(null) }

    // One board per competition per month (the feed refuses date ranges but gives a month at once), only for the
    // competitions that matter to you; each club's own page adds its coming match. A live match refreshes every minute.
    LaunchedEffect(favLeagues, favTeams) {
        val leagueList = ScoresApi.relevant(favLeagues, favTeams)
        val store = HashMap<String, LiveMatch>()
        var round = 0
        while (true) {
            if (round % 5 == 0) {
                val own = coroutineScope {
                    favTeams.map { k -> async { runCatching { ScoresApi.teamMatches(k.substringBefore(':'), k.substringAfter(':')) }.getOrDefault(emptyList()) } }
                        .map { it.await() }.flatten()
                }
                own.forEach { store.putIfAbsent(it.id, it) }
            }
            runCatching { ScoresApi.fetchMonths(7, leagueList) }
                .onSuccess { list -> list.forEach { store[it.id] = it }; failed = false }
                .onFailure { if (store.isEmpty()) failed = true }
            all = store.values.sortedBy { it.startMs }
            round++
            delay(60_000)
        }
    }

    openMatch?.let { MatchSourcesDialog(it) { openMatch = null } }
    club?.let { ClubDialog(it, favTeams, all.orEmpty(), onOpenMatch = { m -> openMatch = m }) { club = null } }

    fun mine(m: LiveMatch) = m.leagueSlug in favLeagues || m.homeId in favIds || m.awayId in favIds
    val hasFavs = favLeagues.isNotEmpty() || favTeams.isNotEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "sub") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("المباريات", "الترتيب", "فريقي").forEachIndexed { i, t ->
                    Text(
                        t, fontWeight = if (sub == i) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp,
                        color = if (sub == i) Color(0xFF1C1206) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.focusFrame(CircleShape).clip(CircleShape)
                            .background(if (sub == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { sub = i }.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(modifier = Modifier.focusFrame(), onClick = onOpenMatchSettings) { Text("⚙ مبارياتي") }
            }
        }
        if (!hasFavs) {
            item(key = "nofav") {
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("اختر دورياتك وأنديتك المفضلة", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("وهنا تطلع لك مبارياتهم فقط، مع الترتيب وصفحة كل نادي. تتزامن مع حسابك.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(modifier = Modifier.focusFrame(), onClick = onOpenMatchSettings) { Text("اختر الآن") }
                }
            }
            return@LazyColumn
        }
        when (sub) {
            0 -> {
                item(key = "days") {
                    val today = LocalDate.now()
                    val matchDays = all.orEmpty().filter { mine(it) }.map { localDay(it.startMs) }.toSet()
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((0..6).toList()) { off ->
                            val d = today.plusDays(off.toLong())
                            val on = off == dayOffset
                            Column(
                                Modifier.width(58.dp).focusFrame(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable { dayOffset = off }.padding(vertical = 9.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val label = when (off) { 0 -> "اليوم"; 1 -> "غداً"; else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("ar")) }
                                Text(label, fontSize = 12.sp, color = if (on) Color(0xFF3D2A00) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                Text("${d.dayOfMonth}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (on) Color(0xFF1C1206) else MaterialTheme.colorScheme.onSurface)
                                Box(Modifier.padding(top = 4.dp).size(6.dp).clip(CircleShape).background(if (d in matchDays) (if (on) Color(0xFF1C1206) else MaterialTheme.colorScheme.primary) else Color.Transparent))
                            }
                        }
                    }
                }
                val list = all
                when {
                    list == null -> item(key = "wait") { Text(if (failed) "تعذّر جلب المباريات (تأكد من الإنترنت)." else "نجلب المباريات…", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else -> {
                        val day = LocalDate.now().plusDays(dayOffset.toLong())
                        val today = list.filter { mine(it) && localDay(it.startMs) == day }
                            .sortedWith(compareBy<LiveMatch> { if (it.state == "in") 0 else 1 }.thenBy { it.startMs })
                        if (today.isEmpty()) item(key = "none") { Text("ما فيه مباريات لدورياتك وأنديتك في هذا اليوم.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        today.groupBy { it.leagueSlug }.forEach { (slug, rows) ->
                            item(key = "lg-$slug") {
                                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        TeamLogo(ScoresApi.leagueLogos[slug], rows.first().league, 26.dp)
                                        Text(rows.first().league, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                    rows.forEach { m -> MatchRow(m, favIds) { openMatch = m } }
                                }
                            }
                        }
                    }
                }
            }
            1 -> item(key = "standings") { StandingsPane(favLeagues, favTeams, favIds) { club = it } }
            else -> item(key = "mine") { MyClubsPane(favTeams, all.orEmpty()) { club = it } }
        }
    }
}

@Composable
private fun MatchRow(m: LiveMatch, favIds: Set<String>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (m.state) {
                "in" -> { Text("● ${m.clock}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                "pre" -> Text(clock(m.startMs), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("انتهت", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            TeamLine(m.homeLogo, m.home, if (m.state == "pre") "" else m.homeScore, m.homeId in favIds)
            TeamLine(m.awayLogo, m.away, if (m.state == "pre") "" else m.awayScore, m.awayId in favIds)
        }
        Text("المصادر ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
    GoalsLine(m)
}

/** Who scored and when, under a started match: «Mitrovic 23', 41' · Jota 58'». */
@Composable
private fun GoalsLine(m: LiveMatch) {
    if (m.state == "pre" || m.goals.isEmpty()) return
    fun side(id: String) = m.goals.filter { it.teamId == id }.groupBy { it.player }
        .map { (p, g) -> p + " " + g.joinToString("، ") { x -> x.minute + (if (x.penalty) " (ج)" else "") + (if (x.own) " (عكسي)" else "") } }
    val h = side(m.homeId); val a = side(m.awayId)
    Column(Modifier.fillMaxWidth().padding(start = 78.dp, end = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (h.isNotEmpty()) Text("⚽ " + m.home + ": " + h.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (a.isNotEmpty()) Text("⚽ " + m.away + ": " + a.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TeamLine(logo: String?, name: String, score: String, mine: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TeamLogo(logo, name, 28.dp)
        Text(name, fontSize = 15.sp, fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal, color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (score.isNotEmpty()) Text(score, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StandingsPane(favLeagues: Set<String>, favTeams: Set<String>, favIds: Set<String>, onClub: (ClubRef) -> Unit) {
    val slugs = remember(favLeagues, favTeams) {
        (favLeagues + favTeams.map { it.substringBefore(':') }).filter { ScoresApi.league(it) != null }.distinct().ifEmpty { listOf("ksa.1") }
    }
    var slug by remember(slugs) { mutableStateOf(slugs.first()) }
    var rows by remember { mutableStateOf<List<StandingRow>?>(null) }
    LaunchedEffect(slug) { rows = null; rows = runCatching { ScoresApi.standings(slug) }.getOrDefault(emptyList()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(slugs) { s ->
                val on = s == slug
                Row(
                    Modifier.focusFrame(CircleShape).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { slug = s }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScoresApi.leagueLogos[s]?.let { TeamLogo(it, "", 20.dp) }
                    Text(ScoresApi.league(s)?.name.orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) Color(0xFF1C1206) else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        val r = rows
        when {
            r == null -> Text("نجلب الترتيب…", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.isEmpty() -> Text("ما قدرنا نجيب ترتيب هذا الدوري من المصدر.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#", Modifier.width(24.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("الفريق", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("لعب", "ف", "ت", "خ", "فارق", "نقاط").forEachIndexed { i, h -> Text(h, Modifier.width(if (i >= 4) 40.dp else 30.dp), fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                r.forEach { row ->
                    val mine = row.teamId in favIds
                    Row(
                        Modifier.fillMaxWidth().background(if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                            .focusFrame(RoundedCornerShape(10.dp)).clickable { onClub(ClubRef(slug, row.teamId, row.team, row.logo)) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${row.pos}", Modifier.width(24.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TeamLogo(row.logo, row.team, 24.dp)
                            Text(row.team, fontSize = 14.sp, fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        listOf(row.played, row.won, row.drawn, row.lost).forEach { Text("$it", Modifier.width(30.dp), fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("${row.diff}", Modifier.width(40.dp), fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${row.points}", Modifier.width(40.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun MyClubsPane(favTeams: Set<String>, matches: List<LiveMatch>, onClub: (ClubRef) -> Unit) {
    var teams by remember { mutableStateOf<List<TeamInfo>>(emptyList()) }
    LaunchedEffect(favTeams) {
        val out = mutableListOf<TeamInfo>()
        for (key in favTeams) {
            val slug = key.substringBefore(':'); val id = key.substringAfter(':')
            runCatching { ScoresApi.teams(slug) }.getOrNull()?.firstOrNull { it.id == id }?.let { out += it }
        }
        teams = out
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (favTeams.isEmpty()) Text("ما اخترت أي نادي بعد. من «⚙ مبارياتي» اختر أنديتك.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        teams.forEach { t ->
            val next = matches.filter { (it.homeId == t.id || it.awayId == t.id) && upcoming(it) }.minByOrNull { it.startMs }
            Row(
                Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onClub(ClubRef(t.leagueSlug, t.id, t.name, t.logo)) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TeamLogo(t.logo, t.name, 46.dp)
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        next?.let { m -> val opp = if (m.homeId == t.id) m.away else m.home; "التالية: $opp · " + (if (m.state == "in") "جارية الآن" else whenText(m.startMs)) } ?: "ما فيه مباراة قريبة",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A club's page: its crest big and faint over its colour, numbers, next match, last results and its place in the table. */
@Composable
private fun ClubDialog(ref: ClubRef, favTeams: Set<String>, matches: List<LiveMatch>, onOpenMatch: (LiveMatch) -> Unit, onClose: () -> Unit) {
    var color by remember { mutableStateOf(Color(0xFF3A3028)) }
    var games by remember { mutableStateOf<List<LiveMatch>>(emptyList()) }
    var table by remember { mutableStateOf<List<StandingRow>>(emptyList()) }
    val container = appContainer()
    val isFav = "${ref.slug}:${ref.id}" in favTeams
    LaunchedEffect(ref) {
        runCatching { ScoresApi.teams(ref.slug) }.getOrNull()?.firstOrNull { it.id == ref.id }?.colorHex?.let { c -> runCatching { color = Color(android.graphics.Color.parseColor(c)) } }
        games = runCatching { ScoresApi.teamMatches(ref.slug, ref.id) }.getOrDefault(emptyList())
        table = runCatching { ScoresApi.standings(ref.slug) }.getOrDefault(emptyList())
    }
    val row = table.firstOrNull { it.teamId == ref.id }
    val all = (games + matches.filter { it.homeId == ref.id || it.awayId == ref.id }).distinctBy { it.leagueSlug + it.id }
    val next = all.filter { upcoming(it) }.minByOrNull { it.startMs }
    val last = all.filter { it.state == "post" }.sortedByDescending { it.startMs }.take(5)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().height(280.dp).background(Brush.verticalGradient(listOf(color, MaterialTheme.colorScheme.background)))) {
                        // The crest again, huge and faint, as the background.
                        Box(Modifier.align(Alignment.CenterStart).padding(start = 0.dp).alpha(0.13f)) { TeamLogo(ref.logo, ref.name, 300.dp) }
                        Row(Modifier.fillMaxWidth().statusBarsPaddingCompat().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(modifier = Modifier.focusFrame(), onClick = onClose) { Text("‹ رجوع", color = Color.White) }
                            TextButton(modifier = Modifier.focusFrame(), onClick = {
                                val key = "${ref.slug}:${ref.id}"
                                val s = container.settings
                                s.setMatchPrefs(s.matchLeagues.value, if (isFav) s.matchTeams.value - key else s.matchTeams.value + key)
                            }) { Text(if (isFav) "★ في مفضلتي" else "☆ أضف لمفضلتي", color = Color(0xFFE8A53A)) }
                        }
                        Row(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TeamLogo(ref.logo, ref.name, 96.dp)
                            Column {
                                Text(ref.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ScoresApi.leagueLogos[ref.slug]?.let { TeamLogo(it, "", 22.dp) }
                                    Text(ScoresApi.league(ref.slug)?.name.orEmpty() + (row?.let { " · المركز ${it.pos}" } ?: ""), fontSize = 14.sp, color = Color(0xFFE6DFD3))
                                }
                            }
                        }
                    }
                }
                row?.let { r ->
                    item {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf("نقطة" to r.points, "فوز" to r.won, "تعادل" to r.drawn, "خسارة" to r.lost).forEach { (t, v) ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$v", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (t == "نقطة") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                next?.let { m ->
                    item {
                        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("المباراة القادمة", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(m.league + " · " + (if (m.state == "in") "جارية الآن" else whenText(m.startMs)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { TeamLogo(m.homeLogo, m.home, 52.dp); Text(m.home, fontSize = 14.sp) }
                                Text(if (m.state == "pre") "VS" else "${m.homeScore} - ${m.awayScore}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { TeamLogo(m.awayLogo, m.away, 52.dp); Text(m.away, fontSize = 14.sp) }
                            }
                            Button(modifier = Modifier.fillMaxWidth().focusFrame(), onClick = { onOpenMatch(m) }) { Text("المصادر") }
                        }
                    }
                }
                if (last.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                            Text("آخر النتائج", Modifier.padding(14.dp), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            last.forEach { m ->
                                val mineHome = m.homeId == ref.id
                                val a = m.homeScore.toIntOrNull() ?: 0; val b = m.awayScore.toIntOrNull() ?: 0
                                val my = if (mineHome) a else b; val their = if (mineHome) b else a
                                val (tag, c) = when { my > their -> "ف" to Color(0xFF2F9E6B); my < their -> "خ" to Color(0xFFD04A4F); else -> "ت" to Color(0xFF7B7368) }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(c), contentAlignment = Alignment.Center) { Text(tag, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                                    TeamLogo(if (mineHome) m.awayLogo else m.homeLogo, "", 24.dp)
                                    Text(if (mineHome) m.away else m.home, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${m.homeScore} - ${m.awayScore}", fontWeight = FontWeight.Bold)
                                    Text(dateText(m.startMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (row != null) {
                    item {
                        val i = table.indexOfFirst { it.teamId == ref.id }
                        val near = table.subList((i - 1).coerceAtLeast(0), (i + 2).coerceAtMost(table.size))
                        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                            Text("موقعه في الدوري", Modifier.padding(14.dp), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            near.forEach { r ->
                                Row(Modifier.fillMaxWidth().background(if (r.teamId == ref.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("${r.pos}", Modifier.width(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TeamLogo(r.logo, r.team, 24.dp)
                                    Text(r.team, Modifier.weight(1f), fontWeight = if (r.teamId == ref.id) FontWeight.Bold else FontWeight.Normal)
                                    Text("${r.points}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.statusBarsPaddingCompat(): Modifier = this.padding(top = 8.dp)
