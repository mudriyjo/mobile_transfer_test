package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.network.BankApi
import com.bank.mobile.core.network.BankApiException
import com.bank.mobile.feature.beneficiaries.CreateBeneficiaryRequest
import com.bank.mobile.testing.FakeNetworkMonitor
import com.bank.mobile.testing.testDatabase
import com.bank.mobile.testing.transferDraft
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TransferRepositoryImplTest {
    private val databasePair = testDatabase()
    private val database = databasePair.first
    private val driver = databasePair.second
    private val localDataSource = TransferLocalDataSource(database)

    @AfterTest
    fun cleanup() {
        driver.close()
    }

    @Test
    fun createTransfer_persistsIntentBeforeRemoteCall() = runTest {
        val checkingApi = object : BankApi {
            override suspend fun createTransfer(operationId: OperationId, request: CreateTransferRequest): TransferDto {
                val record = localDataSource.find(operationId)
                assertNotNull(record, "Intent must exist before remote call")
                assertEquals(TransferStatus.SUBMITTING, record.status)
                throw BankApiException("Remote call failed")
            }
            override suspend fun getAccounts() = error("Not used")
            override suspend fun getBeneficiaries() = error("Not used")
            override suspend fun createBeneficiary(request: CreateBeneficiaryRequest) = error("Not used")
            override suspend fun getTransferByOperationId(operationId: OperationId) = error("Not used")
        }
        val repository = TransferRepositoryImpl(
            remote = TransferRemoteDataSource(checkingApi),
            local = localDataSource,
            networkMonitor = FakeNetworkMonitor(online = true),
        )

        runCatching {
            repository.createTransfer(OperationId("op-1"), transferDraft())
        }
    }

    @Test
    fun createTransfer_preservesOperationOnAmbiguousOutcome() = runTest {
        val failingApi = object : BankApi {
            override suspend fun createTransfer(operationId: OperationId, request: CreateTransferRequest) =
                throw BankApiException("Network timeout")
            override suspend fun getAccounts() = error("Not used")
            override suspend fun getBeneficiaries() = error("Not used")
            override suspend fun createBeneficiary(request: CreateBeneficiaryRequest) = error("Not used")
            override suspend fun getTransferByOperationId(operationId: OperationId) = error("Not used")
        }
        val repository = TransferRepositoryImpl(
            remote = TransferRemoteDataSource(failingApi),
            local = localDataSource,
            networkMonitor = FakeNetworkMonitor(online = true),
        )

        runCatching {
            repository.createTransfer(OperationId("op-2"), transferDraft())
        }

        val record = localDataSource.find(OperationId("op-2"))
        assertNotNull(record, "Operation should still exist after ambiguous failure")
        assertEquals("op-2", record.operationId.value)
        assertEquals(TransferStatus.OUTCOME_UNKNOWN, record.status)
    }
}
