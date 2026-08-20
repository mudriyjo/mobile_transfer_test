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
import com.bank.mobile.ui.components.PrimaryAction
import com.bank.mobile.ui.components.SectionCard
import com.bank.mobile.ui.components.SecondaryAction
import com.bank.mobile.ui.components.StatusBadge
import com.bank.mobile.ui.formatMinor

@Composable
fun TransferResultScreen(
    transfer: TransferResultUiModel,
    onCheckStatus: () -> Unit,
    onDone: () -> Unit,
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
        if (!transfer.status.isTerminal) {
            Text(
                text = "Keep the operation ID and wait for a final status before taking further action.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard {
            Text("Transfer details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            DetailRow("Recipient", transfer.draft.recipientName)
            DetailRow("Amount", transfer.draft.amountMinor.formatMinor(transfer.draft.currency))
            DetailRow("From", transfer.draft.fromAccountLabel)
            if (transfer.draft.note.isNotBlank()) DetailRow("Reference", transfer.draft.note)
            DetailRow("Type", transfer.flowLabel)
        }
        SectionCard {
            Text("Operation identifiers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            DetailRow("Operation ID", transfer.operationId)
            DetailRow("Transfer ID", transfer.transferId)
            DetailRow("Submission attempts", transfer.attemptCount.toString())
            transfer.serverStatus?.let { DetailRow("Bank status", it.headline) }
        }
        if (transfer.timeline.isNotEmpty()) {
            Text("Progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            transfer.timeline.forEach { item ->
                SectionCard {
                    Text(
                        text = if (item.completed) "✓ ${item.title}" else item.title,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    item.timestampLabel?.let { timestamp ->
                        Text(
                            text = timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        if (transfer.canRefreshStatus) {
            SecondaryAction(label = "Check status", onClick = onCheckStatus)
        }
        PrimaryAction(
            label = if (transfer.status.isTerminal) "Done" else "Return to accounts",
            onClick = onDone,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}
