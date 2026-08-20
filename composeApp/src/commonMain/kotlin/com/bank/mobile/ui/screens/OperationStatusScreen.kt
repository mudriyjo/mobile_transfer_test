package com.bank.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bank.mobile.ui.TransferResultUiModel
import com.bank.mobile.ui.components.DetailRow
import com.bank.mobile.ui.components.ErrorNotice
import com.bank.mobile.ui.components.PrimaryAction
import com.bank.mobile.ui.components.SectionCard
import com.bank.mobile.ui.components.StatusBadge
import com.bank.mobile.ui.formatMinor

@Composable
fun OperationStatusScreen(
    transfer: TransferResultUiModel,
    refreshing: Boolean,
    notice: String?,
    error: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusBadge(transfer.status)
        Text(
            text = transfer.status.headline,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = transfer.statusSummary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let { message ->
            SectionCard {
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }
        error?.let { ErrorNotice(it) }
        SectionCard {
            Text("Current record", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            DetailRow("Operation ID", transfer.operationId)
            DetailRow("Transfer ID", transfer.transferId)
            DetailRow("Amount", transfer.draft.amountMinor.formatMinor(transfer.draft.currency))
            DetailRow("Local status", transfer.status.headline)
            DetailRow("Bank status", transfer.serverStatus?.headline ?: "Not confirmed")
            DetailRow("Attempts", transfer.attemptCount.toString())
            if (transfer.updatedAtEpochMillis > 0L) {
                DetailRow("Updated", "Device time ${transfer.updatedAtEpochMillis}")
            }
        }
        if (transfer.timeline.isNotEmpty()) {
            SectionCard {
                Text("Operation progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                transfer.timeline.forEach { item ->
                    Text(
                        text = if (item.completed) "✓ ${item.title}" else "○ ${item.title}",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (transfer.canRefreshStatus) {
            PrimaryAction(
                label = "Refresh bank status",
                onClick = onRefresh,
                loading = refreshing,
                enabled = !refreshing,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
