package com.bank.mobile.feature.beneficiaries

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.network.BankApi
import com.bank.mobile.feature.accounts.AccountDto
import com.bank.mobile.feature.transfer.CreateTransferRequest
import com.bank.mobile.feature.transfer.TransferDto
import com.bank.mobile.testing.testDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class BeneficiaryRepositoryImplTest {
    @Test
    fun refreshReplacesTheCacheTransactionally() = runTest {
        val (database, driver) = testDatabase()
        try {
            val api = BeneficiaryApi(
                listed = listOf(BeneficiaryDto("old", "Old", "•••• 1111", "EUR")),
            )
            val repository = BeneficiaryRepositoryImpl(database, api)
            repository.refresh()
            api.listed = listOf(BeneficiaryDto("new", "New", "•••• 2222", "EUR"))

            repository.refresh()

            assertEquals(listOf("new"), repository.observe().first().map(Beneficiary::id))
        } finally {
            driver.close()
        }
    }

    @Test
    fun failedRefreshRetainsThePreviousDurableCache() = runTest {
        val (database, driver) = testDatabase()
        try {
            val api = BeneficiaryApi(
                listed = listOf(BeneficiaryDto("saved", "Saved", "•••• 1111", "EUR")),
            )
            val repository = BeneficiaryRepositoryImpl(database, api)
            repository.refresh()
            api.listFailure = IllegalStateException("offline")

            assertFailsWith<IllegalStateException> { repository.refresh() }

            assertEquals("saved", repository.observe().first().single().id)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createPersistsOnlyTheMaskedServerResponse() = runTest {
        val (database, driver) = testDatabase()
        try {
            val api = BeneficiaryApi()
            val repository = BeneficiaryRepositoryImpl(database, api)
            val rawIdentifier = "GB82WEST12345698765432"

            repository.create(BeneficiaryDraft("Taylor", rawIdentifier, "EUR"))

            assertEquals(rawIdentifier, api.createdRequest?.accountIdentifier)
            val cached = repository.observe().first().single()
            assertEquals("•••• 5432", cached.maskedAccount)
            assertFalse(cached.toString().contains(rawIdentifier))
        } finally {
            driver.close()
        }
    }
}

private class BeneficiaryApi(
    var listed: List<BeneficiaryDto> = emptyList(),
) : BankApi {
    var listFailure: Exception? = null
    var createdRequest: CreateBeneficiaryRequest? = null

    override suspend fun getAccounts(): List<AccountDto> = emptyList()

    override suspend fun getBeneficiaries(): List<BeneficiaryDto> {
        listFailure?.let { throw it }
        return listed
    }

    override suspend fun createBeneficiary(request: CreateBeneficiaryRequest): BeneficiaryDto {
        createdRequest = request
        return BeneficiaryDto("created", request.displayName, "•••• 5432", request.currency)
    }

    override suspend fun createTransfer(
        operationId: OperationId,
        request: CreateTransferRequest,
    ): TransferDto = error("not used")

    override suspend fun getTransferByOperationId(operationId: OperationId): TransferDto? = null
}
