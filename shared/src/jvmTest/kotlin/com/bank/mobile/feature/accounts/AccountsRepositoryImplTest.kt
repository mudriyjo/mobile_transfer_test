package com.bank.mobile.feature.accounts

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.network.BankApi
import com.bank.mobile.core.time.EpochClock
import com.bank.mobile.feature.beneficiaries.BeneficiaryDto
import com.bank.mobile.feature.beneficiaries.CreateBeneficiaryRequest
import com.bank.mobile.feature.transfer.CreateTransferRequest
import com.bank.mobile.feature.transfer.TransferDto
import com.bank.mobile.testing.testDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountsRepositoryImplTest {
    @Test fun refreshReplacesCacheAndNewRepositoryRestoresIt() = runTest {
        val (database, driver) = testDatabase()
        try {
            val repository = AccountsRepositoryImpl(database, AccountsApi(), EpochClock { 123 })
            repository.refresh()
            val restored = AccountsRepositoryImpl(database, AccountsApi(), EpochClock { 999 }).observeAccounts().first()
            assertEquals("Everyday", restored.single().displayName)
            assertEquals(123, restored.single().updatedAtEpochMillis)
        } finally {
            driver.close()
        }
    }

    @Test
    fun failedRefreshDoesNotDiscardDurableCache() = runTest {
        val (database, driver) = testDatabase()
        try {
            AccountsRepositoryImpl(database, AccountsApi(), EpochClock { 123 }).refresh()
            val failingRepository = AccountsRepositoryImpl(
                database = database,
                api = AccountsApi(failure = IllegalStateException("offline")),
                clock = EpochClock { 999 },
            )

            assertFailsWith<IllegalStateException> { failingRepository.refresh() }

            val cached = failingRepository.observeAccounts().first()
            assertEquals("Everyday", cached.single().displayName)
            assertEquals(123, cached.single().updatedAtEpochMillis)
        } finally {
            driver.close()
        }
    }
}

private class AccountsApi(
    private val failure: Exception? = null,
) : BankApi {
    override suspend fun getAccounts(): List<AccountDto> {
        failure?.let { throw it }
        return listOf(AccountDto("account-everyday", "Everyday", 10_000, "EUR", "now"))
    }
    override suspend fun getBeneficiaries(): List<BeneficiaryDto> = emptyList()
    override suspend fun createBeneficiary(request: CreateBeneficiaryRequest): BeneficiaryDto = error("not used")
    override suspend fun createTransfer(operationId: OperationId, request: CreateTransferRequest): TransferDto = error("not used")
    override suspend fun getTransferByOperationId(operationId: OperationId): TransferDto? = null
}
