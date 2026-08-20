package com.bank.mobile.feature.transfer

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.Money
import com.bank.mobile.db.MobileBankDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TransferLocalDataSource(
    private val database: MobileBankDatabase,
) {
    fun observe(operationId: OperationId): Flow<TransferRecord?> =
        database.mobileBankQueries.selectTransfer(operationId.value)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.toRecord() }

    fun observeAll(): Flow<List<TransferRecord>> =
        database.mobileBankQueries.selectAllTransfers()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toRecord() } }

    fun find(operationId: OperationId): TransferRecord? =
        database.mobileBankQueries.selectTransfer(operationId.value).executeAsOneOrNull()?.toRecord()

    fun findByRemoteId(remoteTransferId: String): TransferRecord? {
        require(remoteTransferId.isNotBlank())
        return database.mobileBankQueries.selectTransferByRemoteId(remoteTransferId)
            .executeAsOneOrNull()
            ?.toRecord()
    }

    fun all(): List<TransferRecord> =
        database.mobileBankQueries.selectAllTransfers().executeAsList().map { it.toRecord() }

    fun recent(limit: Int): List<TransferRecord> {
        require(limit in 1..500)
        return database.mobileBankQueries.selectRecentTransfers(limit.toLong())
            .executeAsList()
            .map { it.toRecord() }
    }

    fun byFlowKind(flowKind: TransferFlowKind): List<TransferRecord> =
        database.mobileBankQueries.selectTransfersByFlowKind(flowKind.name)
            .executeAsList()
            .map { it.toRecord() }

    fun byStatus(status: TransferStatus): List<TransferRecord> =
        database.mobileBankQueries.selectTransfersByLocalStatus(status.name)
            .executeAsList()
            .map { it.toRecord() }

    fun count(status: TransferStatus): Long =
        database.mobileBankQueries.countTransfersByLocalStatus(status.name).executeAsOne()

    fun unfinished(): List<TransferRecord> =
        database.mobileBankQueries.selectUnfinishedTransfers().executeAsList().map { it.toRecord() }

    fun saveIntent(
        operationId: OperationId,
        draft: TransferDraft,
        flowKind: TransferFlowKind,
        nowMillis: Long,
    ) {
        database.mobileBankQueries.insertTransferIntent(
            operation_id = operationId.value,
            flow_kind = flowKind.name,
            from_account_id = draft.fromAccountId,
            beneficiary_id = draft.beneficiaryId,
            amount_minor = draft.amount.minorUnits,
            currency = draft.amount.currency.value,
            reference = draft.reference,
            payload_fingerprint = draft.fingerprint(),
            created_at_epoch_ms = nowMillis,
            updated_at_epoch_ms = nowMillis,
        )
    }

    fun saveResponse(
        requestedOperationId: OperationId,
        draft: TransferDraft,
        flowKind: TransferFlowKind,
        response: TransferDto,
        nowMillis: Long,
    ) {
        val request = draft.toRequest()
        check(response.validateAgainst(request) == TransferResponseValidation.Accepted) {
            "Transfer response payload does not match the submitted draft"
        }
        val status = response.status.toDomain()
        database.mobileBankQueries.saveTransferResponse(
            operation_id = requestedOperationId.value,
            remote_transfer_id = response.transferId,
            flow_kind = flowKind.name,
            from_account_id = draft.fromAccountId,
            beneficiary_id = draft.beneficiaryId,
            amount_minor = draft.amount.minorUnits,
            currency = draft.amount.currency.value,
            reference = draft.reference,
            payload_fingerprint = draft.fingerprint(),
            local_status = status.name,
            server_status = status.name,
            created_at_epoch_ms = nowMillis,
            updated_at_epoch_ms = nowMillis,
            attempt_count = 1,
        )
    }

    fun updateStatus(
        operationId: OperationId,
        response: TransferDto,
        nowMillis: Long,
    ): LocalTransferUpdate {
        val existing = find(operationId) ?: return LocalTransferUpdate.Missing(operationId)
        val validation = response.validateAgainst(existing.draft.toRequest())
        if (validation is TransferResponseValidation.PayloadMismatch) {
            return LocalTransferUpdate.PayloadMismatch(
                operationId = operationId,
                fields = validation.fields,
            )
        }
        val status = response.status.toDomain()
        return when (TransferTransitionPolicy.evaluate(existing.status, status)) {
            TransferTransitionDecision.Unchanged -> LocalTransferUpdate.Unchanged(existing)
            is TransferTransitionDecision.Rejected -> LocalTransferUpdate.TransitionRejected(
                record = existing,
                requestedStatus = status,
            )
            TransferTransitionDecision.Allowed -> {
                database.mobileBankQueries.updateTransferStatus(
                    remoteId = response.transferId,
                    localStatus = status.name,
                    serverStatus = status.name,
                    updatedAt = nowMillis,
                    operationId = operationId.value,
                )
                LocalTransferUpdate.Updated(
                    previous = existing,
                    current = checkNotNull(find(operationId)),
                )
            }
        }
    }

    fun transitionLocalStatus(
        operationId: OperationId,
        status: TransferStatus,
        nowMillis: Long,
    ): LocalTransferUpdate {
        val existing = find(operationId) ?: return LocalTransferUpdate.Missing(operationId)
        return when (TransferTransitionPolicy.evaluate(existing.status, status)) {
            TransferTransitionDecision.Unchanged -> LocalTransferUpdate.Unchanged(existing)
            is TransferTransitionDecision.Rejected -> LocalTransferUpdate.TransitionRejected(
                record = existing,
                requestedStatus = status,
            )
            TransferTransitionDecision.Allowed -> {
                database.mobileBankQueries.updateTransferLocalStatus(
                    status.name,
                    nowMillis,
                    operationId.value,
                )
                LocalTransferUpdate.Updated(existing, checkNotNull(find(operationId)))
            }
        }
    }

    fun recordAttempt(operationId: OperationId, nowMillis: Long): TransferRecord? {
        if (find(operationId) == null) return null
        database.mobileBankQueries.incrementTransferAttempt(nowMillis, operationId.value)
        return find(operationId)
    }

    fun updateServerStatus(
        operationId: OperationId,
        status: TransferStatus?,
        nowMillis: Long,
    ): TransferRecord? {
        if (find(operationId) == null) return null
        database.mobileBankQueries.updateTransferServerStatus(
            status?.name,
            nowMillis,
            operationId.value,
        )
        return find(operationId)
    }

    fun statusCounts(): Map<TransferStatus, Long> = TransferStatus.entries.associateWith(::count)

    fun snapshot(filter: TransferHistoryFilter): TransferHistorySnapshot =
        TransferHistorySnapshot(all(), filter)

    fun markUnknown(operationId: OperationId, nowMillis: Long) {
        database.mobileBankQueries.updateTransferStatus(
            remoteId = null,
            localStatus = TransferStatus.OUTCOME_UNKNOWN.name,
            serverStatus = null,
            updatedAt = nowMillis,
            operationId = operationId.value,
        )
    }

    fun delete(operationId: OperationId) = database.mobileBankQueries.deleteTransfer(operationId.value)
}

sealed interface LocalTransferUpdate {
    data class Updated(
        val previous: TransferRecord,
        val current: TransferRecord,
    ) : LocalTransferUpdate

    data class Unchanged(
        val record: TransferRecord,
    ) : LocalTransferUpdate

    data class Missing(
        val operationId: OperationId,
    ) : LocalTransferUpdate

    data class TransitionRejected(
        val record: TransferRecord,
        val requestedStatus: TransferStatus,
    ) : LocalTransferUpdate

    data class PayloadMismatch(
        val operationId: OperationId,
        val fields: List<TransferPayloadField>,
    ) : LocalTransferUpdate
}

private fun com.bank.mobile.db.Transfer_operation.toRecord(): TransferRecord = TransferRecord(
    operationId = OperationId(operation_id),
    remoteTransferId = remote_transfer_id,
    flowKind = TransferFlowKind.valueOf(flow_kind),
    draft = TransferDraft(
        fromAccountId = from_account_id,
        beneficiaryId = beneficiary_id,
        amount = Money(amount_minor, CurrencyCode(currency)),
        reference = reference,
    ),
    payloadFingerprint = payload_fingerprint,
    status = TransferStatus.valueOf(local_status),
    serverStatus = server_status?.let(TransferStatus::valueOf),
    createdAtEpochMillis = created_at_epoch_ms,
    updatedAtEpochMillis = updated_at_epoch_ms,
    attemptCount = attempt_count,
)
