package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.network.NetworkMonitor
import com.bank.mobile.core.network.NoInternetException
import com.bank.mobile.core.time.DeviceEpochClock
import com.bank.mobile.core.time.EpochClock
import kotlinx.coroutines.flow.Flow

class TransferRepositoryImpl(
    private val remote: TransferRemoteDataSource,
    private val local: TransferLocalDataSource,
    private val networkMonitor: NetworkMonitor,
    private val clock: EpochClock = DeviceEpochClock,
    private val validator: TransferDraftValidator = TransferDraftValidator(),
) : TransferRepository {
    override suspend fun createTransfer(
        operationId: OperationId,
        draft: TransferDraft,
    ): TransferRecord {
        val normalizedDraft = draft.normalized()
        val validation = validator.validate(normalizedDraft)
        require(validation.isValid) {
            "Transfer draft failed validation: ${validation.issues.joinToString { it::class.simpleName.orEmpty() }}"
        }
        if (!networkMonitor.isOnline.value) throw NoInternetException()

        return try {
            val response = remote.create(operationId, normalizedDraft)
            local.saveResponse(
                requestedOperationId = operationId,
                draft = normalizedDraft,
                flowKind = TransferFlowKind.INSTANT,
                response = response,
                nowMillis = clock.nowMillis(),
            )
            checkNotNull(local.find(operationId))
        } catch (error: Exception) {
            // Remove the transient record before surfacing the request failure.
            local.delete(operationId)
            throw error
        }
    }

    override fun observe(operationId: OperationId): Flow<TransferRecord?> = local.observe(operationId)

    override fun observeHistory(): Flow<List<TransferRecord>> = local.observeAll()

    override suspend fun find(operationId: OperationId): TransferRecord? = local.find(operationId)

    override suspend fun history(filter: TransferHistoryFilter): TransferHistorySnapshot =
        local.snapshot(filter)

    override suspend fun refreshStatus(operationId: OperationId): TransferStatusRefreshResult {
        val existing = local.find(operationId)
            ?: return TransferStatusRefreshResult.NotTracked(operationId)
        if (existing.status.isTerminal) {
            return TransferStatusRefreshResult.Unchanged(operationId, existing)
        }

        val response = remote.find(operationId)
            ?: return TransferStatusRefreshResult.NotFound(operationId, existing)

        return when (val update = local.updateStatus(operationId, response, clock.nowMillis())) {
            is LocalTransferUpdate.Updated -> TransferStatusRefreshResult.Updated(
                operationId = operationId,
                previous = update.previous,
                current = update.current,
            )
            is LocalTransferUpdate.Unchanged -> TransferStatusRefreshResult.Unchanged(
                operationId = operationId,
                record = update.record,
            )
            is LocalTransferUpdate.Missing -> TransferStatusRefreshResult.NotTracked(operationId)
            is LocalTransferUpdate.TransitionRejected -> TransferStatusRefreshResult.TransitionRejected(
                operationId = operationId,
                localRecord = update.record,
                serverStatus = update.requestedStatus,
            )
            is LocalTransferUpdate.PayloadMismatch -> TransferStatusRefreshResult.PayloadMismatch(
                operationId = operationId,
                localRecord = existing,
                fields = update.fields,
            )
        }
    }

    override suspend fun reconcilePending(): ReconcileSummary {
        val pending = local.unfinished()
        var updated = 0
        var unresolved = 0
        var unchanged = 0
        var terminal = 0
        val results = mutableListOf<TransferReconcileItem>()
        for (record in pending) {
            val refresh = runCatching { refreshStatus(record.operationId) }.getOrElse {
                unresolved += 1
                results += TransferReconcileItem(
                    operationId = record.operationId,
                    previousStatus = record.status,
                    currentStatus = null,
                    outcome = TransferReconcileOutcome.LOOKUP_FAILED,
                )
                null
            } ?: continue

            when (refresh) {
                is TransferStatusRefreshResult.Updated -> {
                    updated += 1
                    if (refresh.current.status.isTerminal) terminal += 1
                    results += TransferReconcileItem(
                        operationId = record.operationId,
                        previousStatus = refresh.previous.status,
                        currentStatus = refresh.current.status,
                        outcome = TransferReconcileOutcome.UPDATED,
                    )
                }
                is TransferStatusRefreshResult.Unchanged -> {
                    unchanged += 1
                    results += TransferReconcileItem(
                        operationId = record.operationId,
                        previousStatus = record.status,
                        currentStatus = refresh.record.status,
                        outcome = TransferReconcileOutcome.UNCHANGED,
                    )
                }
                is TransferStatusRefreshResult.NotFound,
                is TransferStatusRefreshResult.NotTracked,
                -> {
                    unresolved += 1
                    results += TransferReconcileItem(
                        operationId = record.operationId,
                        previousStatus = record.status,
                        currentStatus = null,
                        outcome = TransferReconcileOutcome.NOT_FOUND,
                    )
                }
                is TransferStatusRefreshResult.TransitionRejected -> {
                    unresolved += 1
                    results += TransferReconcileItem(
                        operationId = record.operationId,
                        previousStatus = record.status,
                        currentStatus = refresh.serverStatus,
                        outcome = TransferReconcileOutcome.TRANSITION_REJECTED,
                    )
                }
                is TransferStatusRefreshResult.PayloadMismatch -> {
                    unresolved += 1
                    results += TransferReconcileItem(
                        operationId = record.operationId,
                        previousStatus = record.status,
                        currentStatus = null,
                        outcome = TransferReconcileOutcome.TRANSITION_REJECTED,
                    )
                }
            }
        }
        return ReconcileSummary(
            checked = pending.size,
            updated = updated,
            unresolved = unresolved,
            unchanged = unchanged,
            terminal = terminal,
            results = results,
        )
    }
}
