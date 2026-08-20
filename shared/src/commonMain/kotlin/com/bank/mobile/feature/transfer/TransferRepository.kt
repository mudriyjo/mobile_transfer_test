package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId
import kotlinx.coroutines.flow.Flow

data class ReconcileSummary(
    val checked: Int,
    val updated: Int,
    val unresolved: Int,
    val unchanged: Int = 0,
    val terminal: Int = 0,
    val results: List<TransferReconcileItem> = emptyList(),
) {
    init {
        require(checked >= 0)
        require(updated >= 0)
        require(unresolved >= 0)
        require(unchanged >= 0)
        require(terminal >= 0)
        require(updated + unresolved + unchanged <= checked)
    }

    val hasUnresolved: Boolean
        get() = unresolved > 0

    val changedAnything: Boolean
        get() = updated > 0
}

data class TransferReconcileItem(
    val operationId: OperationId,
    val previousStatus: TransferStatus,
    val currentStatus: TransferStatus?,
    val outcome: TransferReconcileOutcome,
)

enum class TransferReconcileOutcome {
    UPDATED,
    UNCHANGED,
    NOT_FOUND,
    LOOKUP_FAILED,
    TRANSITION_REJECTED,
}

sealed interface TransferStatusRefreshResult {
    val operationId: OperationId

    data class Updated(
        override val operationId: OperationId,
        val previous: TransferRecord,
        val current: TransferRecord,
    ) : TransferStatusRefreshResult

    data class Unchanged(
        override val operationId: OperationId,
        val record: TransferRecord,
    ) : TransferStatusRefreshResult

    data class NotTracked(
        override val operationId: OperationId,
    ) : TransferStatusRefreshResult

    data class NotFound(
        override val operationId: OperationId,
        val localRecord: TransferRecord,
    ) : TransferStatusRefreshResult

    data class TransitionRejected(
        override val operationId: OperationId,
        val localRecord: TransferRecord,
        val serverStatus: TransferStatus,
    ) : TransferStatusRefreshResult

    data class PayloadMismatch(
        override val operationId: OperationId,
        val localRecord: TransferRecord,
        val fields: List<TransferPayloadField>,
    ) : TransferStatusRefreshResult
}

interface TransferRepository {
    suspend fun createTransfer(operationId: OperationId, draft: TransferDraft): TransferRecord
    fun observe(operationId: OperationId): Flow<TransferRecord?>
    fun observeHistory(): Flow<List<TransferRecord>>
    suspend fun find(operationId: OperationId): TransferRecord?
    suspend fun history(filter: TransferHistoryFilter = TransferHistoryFilter.ALL): TransferHistorySnapshot
    suspend fun refreshStatus(operationId: OperationId): TransferStatusRefreshResult
    suspend fun reconcilePending(): ReconcileSummary
}
