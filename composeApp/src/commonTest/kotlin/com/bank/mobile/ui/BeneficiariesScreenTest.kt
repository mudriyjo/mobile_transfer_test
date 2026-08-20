package com.bank.mobile.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.bank.mobile.feature.beneficiaries.BeneficiaryValidationErrors
import com.bank.mobile.ui.screens.BeneficiariesScreen
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BeneficiariesScreenTest {
    @Test
    fun emptyAndFailureStatesRemainActionable() = runComposeUiTest {
        var retries = 0
        setContent {
            BankTheme {
                BeneficiariesScreen(
                    beneficiaries = emptyList(),
                    initialLoading = false,
                    refreshing = false,
                    saving = false,
                    errorMessage = "Unable to refresh saved recipients",
                    validationErrors = BeneficiaryValidationErrors(),
                    savedBeneficiaryId = null,
                    onRefresh = { retries += 1 },
                    onSave = {},
                    onSelect = {},
                    onClearFeedback = {},
                )
            }
        }

        onNodeWithText("No saved beneficiaries").assertIsDisplayed()
        onNodeWithText("Unable to refresh saved recipients").assertIsDisplayed()
        onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun savedRecipientShowsOnlyMaskedDataAndCanBeSelected() = runComposeUiTest {
        var selected: String? = null
        val beneficiary = BeneficiaryUiModel(
            id = "ben-created",
            displayName = "Taylor Quinn",
            maskedAccount = "•••• 5432",
            currency = "EUR",
        )
        setContent {
            BankTheme {
                BeneficiariesScreen(
                    beneficiaries = listOf(beneficiary),
                    initialLoading = false,
                    refreshing = false,
                    saving = false,
                    errorMessage = null,
                    validationErrors = BeneficiaryValidationErrors(),
                    savedBeneficiaryId = beneficiary.id,
                    onRefresh = {},
                    onSave = {},
                    onSelect = { selected = it },
                    onClearFeedback = {},
                )
            }
        }

        onNodeWithText("•••• 5432  ·  EUR").assertIsDisplayed()
        onNodeWithText("Recipient saved").assertIsDisplayed()
        onNodeWithText("Use for transfer").performClick()
        assertEquals("ben-created", selected)
    }
}
