package com.bank.mobile.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TransferInputSemanticsTest {
    @Test
    fun validAmountEnablesReviewAction() = runComposeUiTest {
        setContent { App(BankUiCoordinator(initialRoute = BankRoute.Transfer)) }

        onNodeWithText("Amount").performTextInput("12.34")

        onNodeWithText("Review transfer").assertIsEnabled()
    }
}
