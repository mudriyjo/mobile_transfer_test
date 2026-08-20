package com.bank.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bank.mobile.ui.AccountUiModel
import com.bank.mobile.ui.BeneficiaryUiModel
import com.bank.mobile.ui.ScheduledPaymentUiModel
import com.bank.mobile.ui.TransferDraftUiModel
import com.bank.mobile.ui.components.ErrorNotice
import com.bank.mobile.ui.components.PrimaryAction
import com.bank.mobile.ui.components.SectionCard
import com.bank.mobile.ui.formatMinor

@Composable
fun ScheduledPaymentsScreen(
    accounts: List<AccountUiModel>,
    beneficiaries: List<BeneficiaryUiModel>,
    payments: List<ScheduledPaymentUiModel>,
    isCreating: Boolean,
    isRunningDue: Boolean,
    notice: String?,
    error: String?,
    onSchedule: (TransferDraftUiModel, Long) -> Unit,
    onRunDue: () -> Unit,
    onDismissFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var accountId by rememberSaveable { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var beneficiaryId by rememberSaveable { mutableStateOf(beneficiaries.firstOrNull()?.id.orEmpty()) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var delayMillis by rememberSaveable { mutableStateOf(scheduleDelays.first().millis) }

    LaunchedEffect(accounts) {
        if (accounts.none { it.id == accountId }) accountId = accounts.firstOrNull()?.id.orEmpty()
    }
    LaunchedEffect(beneficiaries) {
        if (beneficiaries.none { it.id == beneficiaryId }) {
            beneficiaryId = beneficiaries.firstOrNull()?.id.orEmpty()
        }
    }

    val account = accounts.firstOrNull { it.id == accountId }
    val beneficiary = beneficiaries.firstOrNull { it.id == beneficiaryId }
    val amountMinor = amountText.toMinorUnitsOrNull()
    val currenciesMatch = account != null && beneficiary != null && account.currency == beneficiary.currency
    val amountFitsBalance = amountMinor != null && account != null && amountMinor <= account.balanceMinor
    val valid = amountMinor != null && amountMinor > 0 && currenciesMatch && amountFitsBalance

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Create scheduled payment",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Text(
                text = "Execution time is calculated from the time configured on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (error != null) {
            item { ErrorNotice(error) }
        }
        if (notice != null) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(notice, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onDismissFeedback) { Text("Dismiss") }
                    }
                }
            }
        }
        item { Text("From", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(accounts, key = { "scheduled-account-${it.id}" }) { option ->
            ScheduledChoiceCard(
                title = option.name,
                detail = "${option.maskedNumber} · ${option.balanceMinor.formatMinor(option.currency)}",
                selected = option.id == accountId,
                onClick = { accountId = option.id },
            )
        }
        item { Text("To", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(beneficiaries, key = { "scheduled-beneficiary-${it.id}" }) { option ->
            ScheduledChoiceCard(
                title = option.displayName,
                detail = "${option.maskedAccount} · ${option.currency}",
                selected = option.id == beneficiaryId,
                onClick = { beneficiaryId = option.id },
            )
        }
        item {
            OutlinedTextField(
                value = amountText,
                onValueChange = { next -> amountText = next.filter { it.isDigit() || it == '.' || it == ',' } },
                label = { Text("Amount") },
                supportingText = {
                    val support = when {
                        account == null || beneficiary == null -> "Select an account and beneficiary"
                        !currenciesMatch -> "Account and beneficiary currencies must match"
                        amountMinor != null && !amountFitsBalance -> "Amount exceeds the available balance"
                        else -> "Enter an amount in ${account.currency}"
                    }
                    Text(support)
                },
                isError = (account != null && beneficiary != null && !currenciesMatch) ||
                    (amountMinor != null && !amountFitsBalance),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = reference,
                onValueChange = { reference = it.take(70) },
                label = { Text("Reference (optional)") },
                supportingText = { Text("${reference.length}/70") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
        item {
            Text("When", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                scheduleDelays.forEach { delay ->
                    FilterChip(
                        selected = delayMillis == delay.millis,
                        onClick = { delayMillis = delay.millis },
                        label = { Text(delay.label) },
                    )
                }
            }
            PrimaryAction(
                label = "Schedule payment",
                enabled = valid,
                loading = isCreating,
                onClick = {
                    val selectedAccount = account ?: return@PrimaryAction
                    val selectedBeneficiary = beneficiary ?: return@PrimaryAction
                    val selectedAmount = amountMinor ?: return@PrimaryAction
                    onSchedule(
                        TransferDraftUiModel(
                            fromAccountId = selectedAccount.id,
                            toAccountId = selectedBeneficiary.id,
                            recipientName = selectedBeneficiary.displayName,
                            amountMinor = selectedAmount,
                            currency = selectedAccount.currency,
                            note = reference.trim(),
                        ),
                        delayMillis,
                    )
                },
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedButton(
                onClick = onRunDue,
                enabled = !isCreating && !isRunningDue,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (isRunningDue) "Checking due payments…" else "Run due payments now")
            }
        }
        item {
            Text(
                text = "Scheduled payments",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (payments.isEmpty()) {
            item {
                SectionCard {
                    Text("No scheduled payments")
                    Text(
                        "Payments created here will remain available after the app restarts.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(payments, key = { it.id }) { payment ->
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(payment.recipientName, fontWeight = FontWeight.SemiBold)
                    Text(
                        payment.statusLabel,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    payment.amountMinor.formatMinor(payment.currency),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    payment.localExecutionDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Operation ${payment.operationId}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                payment.remoteTransferId?.let {
                    Text("Transfer $it", style = MaterialTheme.typography.bodySmall)
                }
                payment.statusDescription?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduledChoiceCard(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (selected) "Selected" else "Select", modifier = Modifier.width(70.dp))
    }
}

private data class ScheduleDelay(val label: String, val millis: Long)

private val scheduleDelays = listOf(
    ScheduleDelay("Now", 0L),
    ScheduleDelay("In 1 hour", 60L * 60L * 1_000L),
    ScheduleDelay("Tomorrow", 24L * 60L * 60L * 1_000L),
)

private fun String.toMinorUnitsOrNull(): Long? {
    val normalized = trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    val pieces = normalized.split('.')
    if (pieces.size > 2 || pieces.first().isEmpty()) return null
    val major = pieces.first().toLongOrNull() ?: return null
    if (major > Long.MAX_VALUE / 100L) return null
    val decimals = pieces.getOrNull(1).orEmpty()
    if (decimals.length > 2 || decimals.any { !it.isDigit() }) return null
    val minor = decimals.padEnd(2, '0').ifEmpty { "00" }.toLongOrNull() ?: return null
    return major * 100L + minor
}
