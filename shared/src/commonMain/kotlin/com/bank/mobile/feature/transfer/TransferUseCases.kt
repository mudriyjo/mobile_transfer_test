package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class CreateTransferUseCase(
    private val repository: TransferRepository,
    private val validator: TransferDraftValidator = TransferDraftValidator(),
) {
    suspend operator fun invoke(operationId: OperationId, draft: TransferDraft): TransferRecord {
        val report = validator.validate(draft)
        require(report.isValid) { "Transfer draft is not valid" }
        return repository.createTransfer(operationId, draft)
    }

    fun validate(
        draft: TransferDraft,
        context: TransferValidationContext = TransferValidationContext(),
    ): TransferValidationReport = validator.validate(draft, context)
}

class ObserveTransferUseCase(
    private val repository: TransferRepository,
) {
    operator fun invoke(operationId: OperationId): Flow<TransferRecord?> = repository.observe(operationId)

    fun history(): Flow<List<TransferRecord>> = repository.observeHistory()
}

class ReconcileTransferUseCase(
    private val repository: TransferRepository,
) {
    suspend operator fun invoke(): ReconcileSummary = repository.reconcilePending()

    suspend operator fun invoke(operationId: OperationId): TransferRecord? {
        repository.reconcilePending()
        return repository.observe(operationId).first()
    }

    suspend fun refresh(operationId: OperationId): TransferStatusRefreshResult =
        repository.refreshStatus(operationId)

    suspend fun history(
        filter: TransferHistoryFilter = TransferHistoryFilter.ALL,
    ): TransferHistorySnapshot = repository.history(filter)

    suspend fun find(operationId: OperationId): TransferRecord? = repository.find(operationId)
}
