package com.ventouxlabs.bascule.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.ui.theme.statusPalette
import java.util.concurrent.TimeUnit

/**
 * The single answer to "did my weigh-in reach VitalForge" — all six statuses,
 * with `HELD_CONFIRM` ranked top (`00-design.md` §5). See [HistoryViewModel]
 * for the ranking and action semantics; this file is presentation only.
 */
@Composable
fun HistoryScreen(
    onNavigateToScale: () -> Unit,
    viewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(LocalContext.current.applicationContext as BasculeApplication),
    ),
    /**
     * The same instance [ScaleScreen] renders — [BasculeApp] passes it in
     * explicitly scoped to the shared owner, so "Weigh now" here and on Scale
     * always agree on whether a window is running (P25: two views of one
     * running scan must not read as two different answers).
     */
    scaleViewModel: ScaleViewModel = viewModel(
        factory = ScaleViewModel.factory(LocalContext.current.applicationContext as BasculeApplication),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val scaleState by scaleViewModel.uiState.collectAsState()

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
        item {
            val onWeighNowClick =
                if (scaleState.weighNowActive) scaleViewModel::cancelWeighNow else scaleViewModel::weighNow
            WeighNowButton(active = scaleState.weighNowActive, onClick = onWeighNowClick)
        }
        historyBanners(state, onNavigateToScale)

        if (state.rows.isEmpty()) {
            item { EmptyHistory() }
        } else {
            items(state.rows, key = { it.id }) { reading ->
                ReadingRow(
                    reading = reading,
                    unit = state.displayUnit,
                    onConfirm = { viewModel.confirm(reading) },
                    onDecline = { viewModel.decline(reading) },
                    onRetry = { viewModel.retry(reading) },
                )
            }
        }

        item { DiagnosticsSection(state.counters) }
    }
}

/**
 * The scale-state and delivery-health banners at the top of History. Split out
 * of [HistoryScreen] so that block stays under detekt's LongMethod ceiling —
 * not a [Composable] itself, since it only arranges [LazyListScope.item] calls
 * rather than emitting UI directly.
 */
private fun LazyListScope.historyBanners(state: HistoryUiState, onNavigateToScale: () -> Unit) {
    // Null only until the first combine emission lands — skipped rather
    // than defaulted, so a paired-and-watching scale never flashes the
    // "no scale" banner on cold open (see HistoryUiState.captureState).
    state.captureState?.let {
        when (it) {
            CaptureState.OFF -> item {
                Banner(
                    text = "Automatic capture is off. Tap to open the Scale tab and turn it on.",
                    onClick = onNavigateToScale,
                )
            }
            CaptureState.NO_SCALE -> item {
                Banner(
                    text = "No scale registered yet. Tap to open the Scale tab and add one.",
                    onClick = onNavigateToScale,
                )
            }
            CaptureState.WATCHING -> Unit
        }
    }

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
}

/** Shared with [ScaleScreen] so the two screens can never disagree on what a running window looks like. */
@Composable
internal fun WeighNowButton(active: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(if (active) "Waiting for your scale… Cancel" else "Weigh now")
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
            "They'll appear once your scale sends a reading, or tap + to log one manually.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Banner(text: String, onClick: (() -> Unit)? = null) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    // Surface appends its own .clip(shape) after the caller's modifier, so a
                    // clickable ripple added here would bleed past the rounded corners unless
                    // we clip it ourselves first, using the same shape passed to Surface above.
                    Modifier.clip(shape).clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
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
    unit: WeightUnit,
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
                    "${formatWeight(reading, unit)} ${unit.wire}",
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
private fun statusColors(status: ReadingStatus): Pair<Color, Color> {
    val palette = statusPalette(status, isSystemInDarkTheme())
    return palette.container to palette.content
}

private const val STATUS_LABEL_BACKGROUND_ALPHA = 0.6f
private val PENDING_BACKLOG_WARNING_MILLIS = TimeUnit.HOURS.toMillis(1)
