package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alyoushw.watchhub.data.ScoresApi
import com.alyoushw.watchhub.data.TeamInfo

/** «مبارياتي»: choose the leagues and clubs whose matches show in the «مباريات» tab (synced with the account). */
@Composable
fun MatchesSettingsSection() {
    val settings = appContainer().settings
    val leagues by settings.matchLeagues.collectAsStateWithLifecycle()
    val teams by settings.matchTeams.collectAsStateWithLifecycle()
    var all by remember { mutableStateOf<List<TeamInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val out = mutableListOf<TeamInfo>()
        // Domestic leagues only: each club listed once (the cups list the same clubs again). Shown as they arrive.
        for (l in ScoresApi.leagues.filter { ScoresApi.isDomestic(it.slug) }) {
            runCatching { ScoresApi.teams(l.slug) }.getOrNull()?.let { out += it }
            all = out.toList()
        }
    }

    Text("اختر الدوريات والأندية اللي تهمك. تبويب «مباريات» يعرض لك مبارياتهم فقط، وتتزامن اختياراتك مع حسابك على كل أجهزتك.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Text("الدوريات", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        ScoresApi.leagues.forEach { l ->
            val on = l.slug in leagues
            Row(
                Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(12.dp)).clickable { settings.setMatchPrefs(if (on) leagues - l.slug else leagues + l.slug, teams) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TeamLogo(ScoresApi.leagueLogos[l.slug], l.name, 30.dp)
                Column(Modifier.weight(1f)) {
                    Text(l.name, fontWeight = FontWeight.Medium)
                    Text("القناة المعتادة: ${l.keywords.joinToString(" · ")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = on, onCheckedChange = { settings.setMatchPrefs(if (on) leagues - l.slug else leagues + l.slug, teams) })
            }
        }
    }

    // Clubs: pick a league, and all its clubs are listed to tick (no typing needed). Each club shows once, under its own league.
    val domestic = remember { ScoresApi.leagues.filter { ScoresApi.isDomestic(it.slug) } }
    var pickLeague by remember { mutableStateOf(domestic.first().slug) }
    val clubs = remember(all) { all.filter { ScoresApi.isDomestic(it.leagueSlug) }.distinctBy { it.id } }
    Text("أنديتي المفضلة (${teams.size})", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp))
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(domestic) { l ->
            val on = l.slug == pickLeague
            val picked = teams.count { it.startsWith(l.slug + ":") }
            Row(
                Modifier.focusFrame(androidx.compose.foundation.shape.CircleShape).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { pickLeague = l.slug }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(l.name + if (picked > 0) " ($picked)" else "", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) androidx.compose.ui.graphics.Color(0xFF1C1206) else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    OutlinedTextField(
        value = query, onValueChange = { query = it }, label = { Text("بحث داخل الدوري (اختياري)") },
        modifier = Modifier.fillMaxWidth().tvTextField(), keyboardOptions = tvKeyboard(), singleLine = true,
    )
    if (all.isEmpty()) Text("نجلب قائمة الأندية…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val q = query.trim().lowercase()
    val shown = remember(clubs, q, pickLeague) { clubs.filter { it.leagueSlug == pickLeague && (q.isEmpty() || it.name.lowercase().contains(q)) }.sortedBy { it.name } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        shown.forEach { t ->
            val key = "${t.leagueSlug}:${t.id}"
            val on = key in teams
            Row(
                Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(12.dp)).clickable { settings.setMatchPrefs(leagues, if (on) teams - key else teams + key) }.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TeamLogo(t.logo, t.name, 34.dp)
                Text(t.name, Modifier.weight(1f), fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Checkbox(checked = on, onCheckedChange = { settings.setMatchPrefs(leagues, if (on) teams - key else teams + key) })
            }
        }
        if (all.isNotEmpty() && shown.isEmpty()) Text("ما لقينا أندية لهذا الدوري من المصدر.", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("المباريات تطلع للنادي في كل البطولات (الدوري والأبطال وغيرها) من اختيار واحد.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
