package com.bank.mobile.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.bank.mobile.ui.screens.AccountsScreen
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AccountsScreenTest {
    @Test
    fun emptyFailureShowsRetryInsteadOfSampleAccounts() = runComposeUiTest {
        var retries = 0
        setContent {
            AccountsScreen(
                accounts = emptyList(),
                initialLoading = false,
                refreshing = false,
                errorMessage = "Unable to refresh accounts",
                snackbarHostState = remember { SnackbarHostState() },
                onRefresh = { retries += 1 },
                onTransfer = {},
                onScheduledPayments = {},
            )
        }

        onNodeWithText("Accounts unavailable").assertIsDisplayed()
        onNodeWithText("Try again").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun cachedContentMakesStalenessAndRefreshFailureVisible() = runComposeUiTest {
        setContent {
            AccountsScreen(
                accounts = listOf(
                    AccountUiModel(
                        id = "account-everyday",
                        name = "Everyday",
                        maskedNumber = "•••• 0001",
                        balanceMinor = 500,
                        currency = "EUR",
                        isStale = true,
                    ),
                ),
                initialLoading = false,
                refreshing = false,
                errorMessage = "Unable to refresh accounts",
                snackbarHostState = remember { SnackbarHostState() },
                onRefresh = {},
                onTransfer = {},
                onScheduledPayments = {},
            )
        }

        onNodeWithText("Everyday").assertIsDisplayed()
        onNodeWithText("Last known balance — refresh when online").assertIsDisplayed()
        onNodeWithText("Showing saved balances. Unable to refresh accounts").assertIsDisplayed()
    }

    @Test
    fun initialLoadHasAnExplicitLoadingState() = runComposeUiTest {
        setContent {
            AccountsScreen(
                accounts = emptyList(),
                initialLoading = true,
                refreshing = false,
                errorMessage = null,
                snackbarHostState = remember { SnackbarHostState() },
                onRefresh = {},
                onTransfer = {},
                onScheduledPayments = {},
            )
        }

        onNodeWithText("Loading accounts").assertIsDisplayed()
    }
}
