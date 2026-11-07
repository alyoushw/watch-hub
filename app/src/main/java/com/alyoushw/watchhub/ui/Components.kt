package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.alyoushw.watchhub.data.StreamingService
import com.alyoushw.watchhub.data.TmdbApi
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Poster(path: String?, width: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .width(width)
            .height(width * 1.5f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            AsyncImage(
                model = TmdbApi.image(path, "w342"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("🎬", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun ServiceBadge(service: StreamingService, modifier: Modifier = Modifier) {
    Text(
        text = service.label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(service.color))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun ServiceDot(color: Color, letter: String, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun MessageBox(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

@Composable
fun BadgeRow(services: List<StreamingService>) {
    if (services.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        services.forEach { ServiceBadge(it) }
    }
}

fun formatMoney(value: Double, currency: String = "SAR"): String {
    val number = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.2f", value)
    val unit = if (currency == "SAR") "ر.س" else currency
    return "$number $unit"
}

val ArabicDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ar"))

fun daysLabel(days: Long): String = when {
    days <= 0L -> "اليوم"
    days == 1L -> "بكرة"
    days == 2L -> "بعد يومين"
    days in 3L..10L -> "بعد $days أيام"
    else -> "بعد $days يوم"
}
