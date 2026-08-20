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
import com.bank.mobile.ui.TransferDraftUiModel
import com.bank.mobile.ui.components.DetailRow
import com.bank.mobile.ui.components.ErrorNotice
import com.bank.mobile.ui.components.PrimaryAction
import com.bank.mobile.ui.components.SectionCard
import com.bank.mobile.ui.components.SecondaryAction
import com.bank.mobile.ui.formatMinor

@Composable
fun TransferConfirmationScreen(
    draft: TransferDraftUiModel,
    submitting: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Check every detail before confirming.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { ErrorNotice(it) }
        SectionCard {
            Text("Transfer details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            DetailRow("Recipient", draft.recipientName)
            DetailRow("Amount", draft.amountMinor.formatMinor(draft.currency))
            DetailRow("From", draft.fromAccountLabel)
            if (draft.note.isNotBlank()) DetailRow("Reference", draft.note)
        }
        SectionCard {
            Text("Before you continue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ConfirmationPoint(
                number = "1",
                title = "Verify the recipient",
                detail = "Check the recipient name and transfer amount.",
            )
            ConfirmationPoint(
                number = "2",
                title = "Authenticate",
                detail = "Your device will request biometric or device authentication.",
            )
            ConfirmationPoint(
                number = "3",
                title = "Wait for the result",
                detail = "Keep the operation details until the bank reports a final status.",
            )
        }
        Text(
            text = "You will be asked to verify this transfer with your device authentication.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryAction(
            label = "Confirm securely",
            onClick = onConfirm,
            loading = submitting,
            enabled = !submitting,
        )
        SecondaryAction(label = "Edit transfer", onClick = onEdit)
    }
}

@Composable
private fun ConfirmationPoint(
    number: String,
    title: String,
    detail: String,
) {
    Text(
        text = "$number. $title",
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        text = detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}
