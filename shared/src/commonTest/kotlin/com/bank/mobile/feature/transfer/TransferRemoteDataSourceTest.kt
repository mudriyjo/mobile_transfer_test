package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.network.BankApi
import com.bank.mobile.feature.accounts.AccountDto
import com.bank.mobile.feature.beneficiaries.BeneficiaryDto
import com.bank.mobile.feature.beneficiaries.CreateBeneficiaryRequest
import com.bank.mobile.testing.transferDraft
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TransferRemoteDataSourceTest {
    @Test
    fun createForwardsTheCallerOperationId() = runTest {
        val api = RecordingBankApi()
        val operationId = OperationId("caller-operation")

        val response = TransferRemoteDataSource(api).create(operationId, transferDraft())

        assertEquals(operationId, api.createdWithOperationId)
        assertEquals(operationId.value, response.operationId)
    }
}

private class RecordingBankApi : BankApi {
    var createdWithOperationId: OperationId? = null

    override suspend fun createTransfer(
        operationId: OperationId,
        request: CreateTransferRequest,
    ): TransferDto {
        createdWithOperationId = operationId
        return TransferDto(
            transferId = "transfer-1",
            operationId = operationId.value,
            fromAccountId = request.fromAccountId,
            toAccountId = request.toAccountId,
            amountMinorUnits = request.amountMinorUnits,
            currency = request.currency,
            reference = request.reference,
            status = TransferDtoStatus.PROCESSING,
            createdAt = "2026-08-20T12:00:00Z",
            updatedAt = "2026-08-20T12:00:00Z",
        )
    }

    override suspend fun getAccounts(): List<AccountDto> = error("Not used")
    override suspend fun getBeneficiaries(): List<BeneficiaryDto> = error("Not used")
    override suspend fun createBeneficiary(request: CreateBeneficiaryRequest): BeneficiaryDto = error("Not used")
    override suspend fun getTransferByOperationId(operationId: OperationId): TransferDto? = error("Not used")
}
