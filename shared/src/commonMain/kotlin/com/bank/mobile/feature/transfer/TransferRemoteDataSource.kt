package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.network.BankApi

class TransferRemoteDataSource(
    private val api: BankApi,
) {
    suspend fun create(operationId: OperationId, draft: TransferDraft): TransferDto =
        api.createTransfer(operationId, draft.toRequest())

    suspend fun find(operationId: OperationId): TransferDto? = api.getTransferByOperationId(operationId)
}

internal fun TransferDraft.toRequest(): CreateTransferRequest = CreateTransferRequest(
    fromAccountId = fromAccountId,
    toAccountId = beneficiaryId,
    amountMinorUnits = amount.minorUnits,
    currency = amount.currency.value,
    reference = reference,
)
