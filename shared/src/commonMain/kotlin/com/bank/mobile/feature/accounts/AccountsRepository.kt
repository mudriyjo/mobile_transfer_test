package com.bank.mobile.feature.accounts

import kotlinx.coroutines.flow.Flow

interface AccountsRepository {
    fun observeAccounts(): Flow<List<Account>>
    suspend fun refresh()
}
