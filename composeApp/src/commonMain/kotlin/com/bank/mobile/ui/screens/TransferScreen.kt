package com.bank.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bank.mobile.ui.AccountUiModel
import com.bank.mobile.ui.BeneficiaryUiModel
import com.bank.mobile.ui.TransferDraftUiModel
import com.bank.mobile.ui.TransferFormValidationUiModel
import com.bank.mobile.ui.components.PrimaryAction
import com.bank.mobile.ui.components.SectionCard
import com.bank.mobile.ui.formatMinor
import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.MinorUnitAmountParser

@Composable
fun TransferScreen(
    accounts: List<AccountUiModel>,
    beneficiaries: List<BeneficiaryUiModel>,
    initialBeneficiaryId: String? = null,
    onReview: (TransferDraftUiModel) -> Unit,
    onManageBeneficiaries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var accountId by rememberSaveable { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var beneficiaryId by rememberSaveable { mutableStateOf(beneficiaries.firstOrNull()?.id.orEmpty()) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(initialBeneficiaryId, beneficiaries) {
        val requested = beneficiaries.firstOrNull { it.id == initialBeneficiaryId }
        if (requested != null) beneficiaryId = requested.id
    }
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
    val amountMinor = account?.currency
        ?.let(::CurrencyCode)
        ?.let { currency -> MinorUnitAmountParser.parse(amountText, currency)?.minorUnits }
    val validation = validateTransferForm(account, beneficiary, amountText, amountMinor, note)
    val valid = validation.isValid

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionTitle("From") }
        if (accounts.isEmpty()) {
            item { FieldError("No eligible source account is available") }
        }
        items(accounts, key = { it.id }) { option ->
            ChoiceCard(
                title = option.name,
                detail = "${option.maskedNumber}  ·  ${option.balanceMinor.formatMinor(option.currency)}",
                selected = option.id == accountId,
                onClick = { accountId = option.id },
            )
        }
        validation.accountError?.let { message -> item { FieldError(message) } }
        item { SectionTitle("To") }
        if (beneficiaries.isEmpty()) {
            item { FieldError("Add a beneficiary before creating a transfer") }
        }
        items(beneficiaries, key = { it.id }) { option ->
            ChoiceCard(
                title = option.displayName,
                detail = option.maskedAccount,
                selected = option.id == beneficiaryId,
                onClick = { beneficiaryId = option.id },
            )
        }
        validation.beneficiaryError?.let { message -> item { FieldError(message) } }
        item {
            Text(
                text = "Manage beneficiaries",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onManageBeneficiaries).padding(vertical = 8.dp),
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { next ->
                    amountText = next
                        .filter { it.isDigit() || it == '.' || it == ',' }
                        .take(16)
                },
                label = { Text("Amount") },
                supportingText = {
                    Text(
                        validation.amountError
                            ?: "Available ${account?.balanceMinor?.formatMinor(account.currency) ?: "balance unavailable"}",
                    )
                },
                isError = validation.amountError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(70) },
                label = { Text("Reference (optional)") },
                supportingText = { Text(validation.referenceError ?: "${note.length}/70") },
                isError = validation.referenceError != null,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            if (account?.isStale == true) {
                Text(
                    text = "The displayed balance was loaded from saved data and may have changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (account != null && beneficiary != null && validation.isValid) {
                SectionCard(modifier = Modifier.padding(top = 14.dp)) {
                    Text("Ready to review", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${amountMinor?.formatMinor(account.currency)} to ${beneficiary.displayName}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            PrimaryAction(
                label = "Review transfer",
                enabled = valid,
                onClick = {
                    val selectedAccount = account ?: return@PrimaryAction
                    val selectedBeneficiary = beneficiary ?: return@PrimaryAction
                    val selectedAmount = amountMinor ?: return@PrimaryAction
                    onReview(
                        TransferDraftUiModel(
                            fromAccountId = selectedAccount.id,
                            toAccountId = selectedBeneficiary.id,
                            recipientName = selectedBeneficiary.displayName,
                            amountMinor = selectedAmount,
                            currency = selectedAccount.currency,
                            note = note.trim(),
                            fromAccountLabel = "${selectedAccount.name} ${selectedAccount.maskedNumber}",
                        ),
                    )
                },
                modifier = Modifier.padding(top = 20.dp, bottom = 28.dp),
            )
        }
    }
}

private fun validateTransferForm(
    account: AccountUiModel?,
    beneficiary: BeneficiaryUiModel?,
    amountText: String,
    amountMinor: Long?,
    reference: String,
): TransferFormValidationUiModel {
    val accountError = if (account == null) "Select a source account" else null
    val beneficiaryError = when {
        beneficiary == null -> "Select a beneficiary"
        account != null && beneficiary.currency != account.currency ->
            "The beneficiary currency does not match the source account"
        else -> null
    }
    val amountError = when {
        amountText.isBlank() -> "Enter an amount"
        amountMinor == null -> "Enter a valid amount with no more than two decimal places"
        amountMinor <= 0L -> "Amount must be greater than zero"
        account != null && amountMinor > account.balanceMinor -> "Amount exceeds the displayed balance"
        else -> null
    }
    val referenceError = when {
        reference.length > 70 -> "Reference must contain at most 70 characters"
        reference.any(Char::isISOControl) -> "Reference contains an unsupported character"
        else -> null
    }
    return TransferFormValidationUiModel(
        amountError = amountError,
        accountError = accountError,
        beneficiaryError = beneficiaryError,
        referenceError = referenceError,
    )
}

@Composable
private fun FieldError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SectionTitle(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun ChoiceCard(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    SectionCard(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected) {
            Text(
                text = "Selected",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}
