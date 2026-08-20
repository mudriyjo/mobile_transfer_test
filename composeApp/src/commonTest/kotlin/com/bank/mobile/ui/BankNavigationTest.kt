package com.bank.mobile.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class BankNavigationTest {
    @Test
    fun transferEntryPointOpensTransferForm() = runComposeUiTest {
        setContent { App(BankUiCoordinator()) }

        onNodeWithText("Make a transfer").performClick()

        onNodeWithText("New transfer").assertIsDisplayed()
        onNodeWithText("Review transfer").fetchSemanticsNode()
    }
}
