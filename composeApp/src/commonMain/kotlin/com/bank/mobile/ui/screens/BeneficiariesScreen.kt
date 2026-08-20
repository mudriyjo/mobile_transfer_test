package com.bank.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bank.mobile.feature.beneficiaries.BeneficiaryDraft
import com.bank.mobile.feature.beneficiaries.BeneficiaryValidationErrors
import com.bank.mobile.ui.BeneficiaryUiModel
import com.bank.mobile.ui.components.ErrorNotice
import com.bank.mobile.ui.components.PrimaryAction
import com.bank.mobile.ui.components.SecondaryAction
import com.bank.mobile.ui.components.SectionCard

@Composable
fun BeneficiariesScreen(
    beneficiaries: List<BeneficiaryUiModel>,
    initialLoading: Boolean,
    refreshing: Boolean,
    saving: Boolean,
    errorMessage: String?,
    validationErrors: BeneficiaryValidationErrors,
    savedBeneficiaryId: String?,
    onRefresh: () -> Unit,
    onSave: (BeneficiaryDraft) -> Unit,
    onSelect: (String) -> Unit,
    onClearFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayName by rememberSaveable { mutableStateOf("") }
    var accountIdentifier by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("EUR") }

    LaunchedEffect(savedBeneficiaryId) {
        if (savedBeneficiaryId != null) {
            displayName = ""
            accountIdentifier = ""
            currency = "EUR"
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Saved beneficiaries",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Only a masked account reference is kept on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (initialLoading) {
            item {
                CircularProgressIndicator(
                    modifier = Modifier.semantics {
                        contentDescription = "Loading saved beneficiaries"
                    },
                )
            }
        }

        if (errorMessage != null) {
            item {
                ErrorNotice(errorMessage)
                SecondaryAction(
                    label = "Try again",
                    onClick = onRefresh,
                )
            }
        }

        if (!initialLoading && beneficiaries.isEmpty()) {
            item {
                SectionCard {
                    Text("No saved beneficiaries", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Add a recipient below to make them available for transfers.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (beneficiaries.isNotEmpty()) {
            items(beneficiaries, key = { it.id }) { beneficiary ->
                SectionCard(
                    modifier = Modifier.clickable { onSelect(beneficiary.id) },
                ) {
                    Text(beneficiary.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${beneficiary.maskedAccount}  ·  ${beneficiary.currency}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Use for transfer",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item {
                SecondaryAction(
                    label = if (refreshing) "Refreshing…" else "Refresh saved recipients",
                    onClick = onRefresh,
                )
            }
        }

        savedBeneficiaryId?.let { savedId ->
            item {
                val saved = beneficiaries.firstOrNull { it.id == savedId }
                SectionCard {
                    Text(
                        "Recipient saved",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (saved != null) {
                        Text("${saved.displayName}  ·  ${saved.maskedAccount}")
                    }
                }
            }
        }

        item {
            Text(
                "Add a beneficiary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = { next ->
                    displayName = next.take(60)
                    onClearFeedback()
                },
                label = { Text("Display name") },
                isError = validationErrors.displayName != null,
                supportingText = validationErrors.displayName?.let { message ->
                    { Text(message) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            OutlinedTextField(
                value = accountIdentifier,
                onValueChange = { next ->
                    accountIdentifier = next.take(42)
                    onClearFeedback()
                },
                label = { Text("Account identifier") },
                isError = validationErrors.accountIdentifier != null,
                supportingText = {
                    Text(
                        validationErrors.accountIdentifier
                            ?: "Sent once to the bank; only the final four characters are retained",
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            OutlinedTextField(
                value = currency,
                onValueChange = { next ->
                    currency = next.filter(Char::isLetter).take(3).uppercase()
                    onClearFeedback()
                },
                label = { Text("Currency") },
                isError = validationErrors.currency != null,
                supportingText = validationErrors.currency?.let { message ->
                    { Text(message) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            PrimaryAction(
                label = "Save beneficiary",
                loading = saving,
                enabled = !refreshing,
                onClick = {
                    onSave(
                        BeneficiaryDraft(
                            displayName = displayName,
                            accountIdentifier = accountIdentifier,
                            currency = currency,
                        ),
                    )
                },
                modifier = Modifier.padding(top = 18.dp, bottom = 28.dp),
            )
        }
    }
}
