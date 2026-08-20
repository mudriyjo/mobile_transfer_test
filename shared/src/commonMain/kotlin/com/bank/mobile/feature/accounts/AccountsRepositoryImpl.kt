package com.bank.mobile.feature.accounts

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.Money
import com.bank.mobile.core.network.BankApi
import com.bank.mobile.core.time.DeviceEpochClock
import com.bank.mobile.core.time.EpochClock
import com.bank.mobile.db.MobileBankDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AccountsRepositoryImpl(
    private val database: MobileBankDatabase,
    private val api: BankApi,
    private val clock: EpochClock = DeviceEpochClock,
) : AccountsRepository {
    override fun observeAccounts(): Flow<List<Account>> =
        database.mobileBankQueries.selectAccounts().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row ->
                Account(
                    id = row.account_id,
                    displayName = row.display_name,
                    maskedNumber = row.masked_number,
                    balance = Money(row.balance_minor, CurrencyCode(row.currency)),
                    updatedAtEpochMillis = row.updated_at_epoch_ms,
                )
            }
        }

    override suspend fun refresh() {
        val receivedAt = clock.nowMillis()
        val accounts = api.getAccounts().map { it.toDomain(receivedAt) }
        database.transaction {
            database.mobileBankQueries.deleteAccounts()
            accounts.forEach { account ->
                database.mobileBankQueries.replaceAccount(
                    account_id = account.id,
                    display_name = account.displayName,
                    masked_number = account.maskedNumber,
                    balance_minor = account.balance.minorUnits,
                    currency = account.balance.currency.value,
                    updated_at_epoch_ms = account.updatedAtEpochMillis,
                )
            }
        }
    }
}
