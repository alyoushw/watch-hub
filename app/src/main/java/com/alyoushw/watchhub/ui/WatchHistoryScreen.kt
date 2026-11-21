package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchHistoryScreen(onBack: () -> Unit) {
    val container = appContainer()
    val st = remember { container.stats.thisMonth() }
    val genres = container.recommender.topGenres
    val history by container.history.entries.collectAsStateWithLifecycle()

    fun hours(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} س ${m % 60} د" else "$m د"
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("ملخص المشاهدات") },
                navigationIcon = {
                    IconButton(modifier = Modifier.focusFrame(), onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .tvScrollAssist(scroll)
                .verticalScroll(scroll)
                .readableWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (st.totalMs >= 60_000) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WatchStatCard(
                        label = "وقت المشاهدة",
                        value = hours(st.totalMs),
                        modifier = Modifier.weight(1f),
                    )
                    val episodeMs = (st.byKind["episode"] ?: 0L) + (st.byKind["anime_episode"] ?: 0L)
                    if (episodeMs > 0) {
                        WatchStatCard(
                            label = "مسلسلات وأنمي",
                            value = hours(episodeMs),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val movieMs = st.byKind["movie"] ?: 0L
                    if (movieMs > 0) {
                        WatchStatCard(
                            label = "أفلام",
                            value = hours(movieMs),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (st.finished > 0) {
                        WatchStatCard(
                            label = "اكتملت",
                            value = "${st.finished}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (st.topTitles.isNotEmpty()) {
                SectionTitle("🏆 الأكثر مشاهدة هذا الشهر")
                st.topTitles.forEach { (title, ms) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(hours(ms), style = MaterialTheme.typography.labelMedium, color = Gold)
                    }
                }
            }

            if (genres.isNotEmpty()) {
                SectionTitle("🎭 ذوقك")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    genres.forEach { g ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(g, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }

            val recent = remember(history) { history.filter { !it.isLive }.take(30) }
            if (recent.isNotEmpty()) {
                SectionTitle("آخر ما شاهدته")
                recent.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(width = 52.dp, height = 74.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            if (!entry.image.isNullOrBlank()) {
                                AsyncImage(
                                    model = entry.image,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            entry.subtitle?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            if (entry.durationMs > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(entry.progress)
                                            .fillMaxSize()
                                            .background(Gold),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    "ما يوجد تاريخ مشاهدة بعد.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun WatchStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Gold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
