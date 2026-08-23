package com.ventouxlabs.bascule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import java.util.concurrent.TimeUnit

/**
 * The single answer to "did my weigh-in reach VitalForge" — all six statuses,
 * with `HELD_CONFIRM` ranked top (`00-design.md` §5). See [HistoryViewModel]
 * for the ranking and action semantics; this file is presentation only.
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(LocalContext.current.applicationContext as BasculeApplication),
    ),
) {
    val state by viewModel.uiState.collectAsState()

    // Banners and diagnostics must render even with zero rows — O-11.4's
    // whole point is that a session producing no reading (E7) inserts no row
    // at all, so an early return on an empty list would hide exactly the
    // signal ("weigh-ins are silently failing") this screen exists to show,
    // making that failure indistinguishable from "you didn't weigh yourself".
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.hasBlockedAuth) {
            item { Banner(text = "VitalForge needs your login again before more weigh-ins can send.") }
        }
        if (state.hasFailedPermanent) {
            item { Banner(text = "Some weigh-ins couldn't be delivered. Retry them below.") }
        }
        state.oldestPendingAgeMillis?.let { ageMillis ->
            if (ageMillis >= PENDING_BACKLOG_WARNING_MILLIS) {
                item { Banner(text = "Weigh-ins have been waiting to send for ${formatRelativeAge(ageMillis)}.") }
            }
        }

        if (state.rows.isEmpty()) {
            item { EmptyHistory() }
        } else {
            items(state.rows, key = { it.id }) { reading ->
                ReadingRow(
                    reading = reading,
                    onConfirm = { viewModel.confirm(reading) },
                    onDecline = { viewModel.decline(reading) },
                    onRetry = { viewModel.retry(reading) },
                )
            }
        }

        item { DiagnosticsSection(state.counters) }
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No weigh-ins yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Step on the scale, or add one manually.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Banner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ReadingRow(
    reading: ReadingEntity,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
) {
    val (containerColor, contentColor) = statusColors(reading.status)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${formatWeight(reading)} ${reading.displayUnit}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusLabel(reading.status)
            }
            Text(
                formatRelativeAge(System.currentTimeMillis() - reading.capturedAtMillis) + " ago",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            when (reading.status) {
                ReadingStatus.HELD_CONFIRM -> Row(modifier = Modifier.padding(top = 12.dp)) {
                    Button(onClick = onConfirm) { Text("Yes, that's me") }
                    OutlinedButton(onClick = onDecline, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Not me")
                    }
                }

                ReadingStatus.FAILED_PERMANENT -> Button(
                    onClick = onRetry,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Retry") }

                // BLOCKED_AUTH resolves app-wide via re-login, not per row.
                // DECLINED is terminal by design (ADR-006) — offering a retry
                // here is exactly the one-tap path that would defeat the hold.
                // Spelled out rather than `else` so adding a new status forces
                // a compile error here instead of silently falling through.
                ReadingStatus.PENDING,
                ReadingStatus.SENT,
                ReadingStatus.BLOCKED_AUTH,
                ReadingStatus.DECLINED,
                -> Unit
            }
        }
    }
}

@Composable
private fun StatusLabel(status: ReadingStatus) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = STATUS_LABEL_BACKGROUND_ALPHA),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            status.name.lowercase().replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DiagnosticsSection(counters: Map<DiagnosticsCounterKey, Int>) {
    val nonZero = counters.filterValues { it > 0 }
    if (nonZero.isEmpty()) return

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
        nonZero.entries.sortedBy { it.key.name }.forEach { (key, value) ->
            Text(
                "${key.name.lowercase().replace('_', ' ')}: $value",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun statusColors(status: ReadingStatus): Pair<Color, Color> = when (status) {
    ReadingStatus.HELD_CONFIRM ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    ReadingStatus.BLOCKED_AUTH, ReadingStatus.FAILED_PERMANENT ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    ReadingStatus.SENT ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    ReadingStatus.PENDING, ReadingStatus.DECLINED ->
        MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
}

private fun formatWeight(reading: ReadingEntity): String {
    val unit = WeightUnit.entries.firstOrNull { it.wire == reading.displayUnit } ?: WeightUnit.KILOGRAMS
    return "%.1f".format(unit.fromKilograms(reading.weightKg))
}

/** Coarse, dependency-free relative-age formatting — no locale-aware library is in this project's dependency set. */
private fun formatRelativeAge(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val days = TimeUnit.MILLISECONDS.toDays(millis)
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "${minutes}m"
        hours < HOURS_PER_DAY -> "${hours}h"
        else -> "${days}d"
    }
}

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val STATUS_LABEL_BACKGROUND_ALPHA = 0.6f
private val PENDING_BACKLOG_WARNING_MILLIS = TimeUnit.HOURS.toMillis(1)
