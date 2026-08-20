package com.bank.mobile.ui

import androidx.compose.runtime.Composable

@Composable
fun App(coordinator: BankUiCoordinator) {
    BankTheme {
        BankNavigation(coordinator)
    }
}
