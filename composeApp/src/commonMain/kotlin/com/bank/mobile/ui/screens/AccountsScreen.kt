package com.bank.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bank.mobile.ui.AccountUiModel
import com.bank.mobile.ui.components.ErrorNotice
import com.bank.mobile.ui.components.PrimaryAction
import com.bank.mobile.ui.components.SectionCard
import com.bank.mobile.ui.components.SecondaryAction
import com.bank.mobile.ui.formatMinor

@Composable
fun AccountsScreen(
    accounts: List<AccountUiModel>,
    initialLoading: Boolean,
    refreshing: Boolean,
    errorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onTransfer: () -> Unit,
    onScheduledPayments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            accounts.isEmpty() && (initialLoading || refreshing) -> AccountsLoading()
            accounts.isEmpty() && errorMessage != null -> AccountsUnavailable(
                message = errorMessage,
                onRetry = onRefresh,
            )
            accounts.isEmpty() -> AccountsEmpty(onRefresh = onRefresh)
            else -> AccountsContent(
                accounts = accounts,
                refreshing = refreshing,
                errorMessage = errorMessage,
                onRefresh = onRefresh,
                onTransfer = onTransfer,
                onScheduledPayments = onScheduledPayments,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
private fun AccountsContent(
    accounts: List<AccountUiModel>,
    refreshing: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onTransfer: () -> Unit,
    onScheduledPayments: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Good morning",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Your balances",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    Text("Refresh")
                }
            }
            if (refreshing) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = "Updating account balances" },
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Updating balances",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        errorMessage?.let { message ->
            item {
                ErrorNotice("Showing saved balances. $message")
            }
        }
        items(accounts, key = { it.id }) { account ->
            AccountCard(account)
        }
        item {
            Spacer(Modifier.height(4.dp))
            PrimaryAction(label = "Make a transfer", onClick = onTransfer)
            Spacer(Modifier.height(10.dp))
            SecondaryAction(label = "Scheduled payments", onClick = onScheduledPayments)
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun AccountsLoading() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = "Loading accounts" },
        )
        Spacer(Modifier.height(16.dp))
        Text("Loading accounts")
    }
}

@Composable
private fun AccountsUnavailable(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Accounts unavailable",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        ErrorNotice(message)
        PrimaryAction(label = "Try again", onClick = onRetry)
    }
}

@Composable
private fun AccountsEmpty(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No accounts available",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Refresh to check for accounts linked to this profile.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryAction(label = "Refresh accounts", onClick = onRefresh)
    }
}

@Composable
private fun AccountCard(account: AccountUiModel) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text(account.name, fontWeight = FontWeight.SemiBold)
                Text(
                    account.maskedNumber,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = account.balanceMinor.formatMinor(account.currency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        if (account.isStale) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Last known balance — refresh when online",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
