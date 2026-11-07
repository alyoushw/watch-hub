package com.alyoushw.watchhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alyoushw.watchhub.data.BillingCycle
import com.alyoushw.watchhub.data.StreamingService
import com.alyoushw.watchhub.data.Subscription
import com.alyoushw.watchhub.data.launchService
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen() {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by container.store.data.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Subscription?>(null) }
    var creating by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val subs = data.subscriptions.sortedBy { it.nextRenewal(today) }
    // Totals are per currency so mixed currencies are never added together.
    val monthlyByCurrency = data.subscriptions.groupBy { it.currency }
        .mapValues { (_, list) -> list.sumOf { it.monthlyCost } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("اشتراكاتي") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                    modifier = Modifier.focusFrame(),
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("اشتراك جديد") },
            )
        },
    ) { padding ->
        if (subs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                MessageBox(
                    title = "ما أضفت اشتراكات",
                    body = "سجّل اشتراكاتك في نتفلكس وبرايم وكرنشي رول وغيرها، وبنحسب لك كم تدفع بالشهر ومتى يتجدد كل واحد.",
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("تدفع تقريباً بالشهر", style = MaterialTheme.typography.bodyMedium)
                            monthlyByCurrency.forEach { (currency, total) ->
                                Text(
                                    formatMoney(roundMoney(total), currency),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "يعني ${formatMoney(roundMoney(total * 12), currency)} بالسنة",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                items(subs, key = { it.id }) { sub ->
                    SubscriptionRow(
                        sub = sub,
                        today = today,
                        onOpen = StreamingService.forKey(sub.serviceKey)?.let { service -> { context.launchService(service) } },
                        onEdit = { editing = sub },
                        onDelete = { scope.launch { container.store.removeSubscription(sub.id) } },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        SubscriptionDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { sub ->
                scope.launch { container.store.upsertSubscription(sub) }
                creating = false
                editing = null
            },
        )
    }
}

private fun roundMoney(v: Double) = Math.round(v * 100) / 100.0

@Composable
private fun SubscriptionRow(
    sub: Subscription,
    today: LocalDate,
    onOpen: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val service = StreamingService.forKey(sub.serviceKey)
    val next = sub.nextRenewal(today)
    val days = ChronoUnit.DAYS.between(today, next)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServiceDot(
                    color = service?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline,
                    letter = sub.name.take(1),
                )
                Column(Modifier.weight(1f)) {
                    Text(sub.name, fontWeight = FontWeight.Bold)
                    Text(
                        "${formatMoney(sub.price, sub.currency)} · ${sub.cycle.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(modifier = Modifier.focusFrame(), onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "تعديل") }
                IconButton(modifier = Modifier.focusFrame(), onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "يتجدد ${daysLabel(days)} (${next.format(ArabicDate)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (days <= 3) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onOpen != null) {
                    OutlinedButton(modifier = Modifier.focusFrame(), onClick = onOpen) { Text("افتح التطبيق") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionDialog(
    initial: Subscription?,
    onDismiss: () -> Unit,
    onSave: (Subscription) -> Unit,
) {
    var serviceKey by remember { mutableStateOf(initial?.serviceKey) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var price by remember { mutableStateOf(initial?.price?.let { formatMoney(it, "").trim() }.orEmpty()) }
    var cycle by remember { mutableStateOf(initial?.cycle ?: BillingCycle.MONTHLY) }
    var renewal by remember {
        mutableStateOf(initial?.let { LocalDate.ofEpochDay(it.renewalEpochDay) } ?: LocalDate.now().plusMonths(1))
    }
    var pickingDate by remember { mutableStateOf(false) }

    val parsedPrice = price.replace(',', '.').replace('٫', '.').toDoubleOrNull()
    val valid = name.isNotBlank() && parsedPrice != null && parsedPrice > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "اشتراك جديد" else "تعديل الاشتراك") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamingService.entries.forEach { service ->
                        FilterChip(
                    modifier = Modifier.focusFrame(),
                            selected = serviceKey == service.key,
                            onClick = {
                                // Keep a custom name the user typed; otherwise follow the chosen service.
                                val previous = StreamingService.forKey(serviceKey)?.label
                                if (name.isBlank() || name == previous) name = service.label
                                serviceKey = service.key
                            },
                            label = { Text(service.label) },
                        )
                    }
                    FilterChip(
                    modifier = Modifier.focusFrame(),
                        selected = serviceKey == null,
                        onClick = {
                            if (StreamingService.forKey(serviceKey)?.label == name) name = ""
                            serviceKey = null
                        },
                        label = { Text("غيرها") },
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("الاسم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("السعر (ر.س)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BillingCycle.entries.forEach { c ->
                        FilterChip(modifier = Modifier.focusFrame(), selected = cycle == c, onClick = { cycle = c }, label = { Text(c.label) })
                    }
                }
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                    Text("التجديد: ${renewal.format(ArabicDate)}")
                }
            }
        },
        confirmButton = {
            TextButton(
                    modifier = Modifier.focusFrame(),
                enabled = valid,
                onClick = {
                    onSave(
                        Subscription(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            serviceKey = serviceKey,
                            name = name.trim(),
                            price = parsedPrice ?: 0.0,
                            currency = initial?.currency ?: "SAR",
                            cycle = cycle,
                            renewalEpochDay = renewal.toEpochDay(),
                        ),
                    )
                },
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = onDismiss) { Text("إلغاء") } },
    )

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = renewal.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(modifier = Modifier.focusFrame(), onClick = {
                    state.selectedDateMillis?.let {
                        renewal = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickingDate = false
                }) { Text("تم") }
            },
            dismissButton = { TextButton(modifier = Modifier.focusFrame(), onClick = { pickingDate = false }) { Text("إلغاء") } },
        ) {
            DatePicker(state = state)
        }
    }
}
