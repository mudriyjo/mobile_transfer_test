package com.bank.mobile.feature.transfer

sealed interface TransferMutation {
    data class DraftSelected(
        val draft: TransferDraft,
        val validation: TransferValidationReport = TransferValidationReport.Valid,
    ) : TransferMutation
    data object AuthenticationStarted : TransferMutation
    data class AuthenticationFailed(val failure: TransferFailure) : TransferMutation
    data object SubmissionStarted : TransferMutation
    data class OperationSelected(val operationId: com.bank.mobile.core.ids.OperationId) : TransferMutation
    data class SubmissionSucceeded(val transfer: TransferRecord) : TransferMutation
    data class SubmissionFailed(
        val message: String,
        val kind: TransferFailureKind = TransferFailureKind.TEMPORARY,
        val canTryAgain: Boolean = true,
    ) : TransferMutation
    data object StatusRefreshStarted : TransferMutation
    data class StatusRefreshFinished(val result: TransferStatusRefreshResult) : TransferMutation
    data class StatusRefreshFailed(val failure: TransferFailure) : TransferMutation
    data object ReconciliationStarted : TransferMutation
    data class ReconciliationFinished(val summary: ReconcileSummary) : TransferMutation
    data class HistoryLoaded(val snapshot: TransferHistorySnapshot) : TransferMutation
    data class HistoryFilterChanged(val filter: TransferHistoryFilter) : TransferMutation
    data object ErrorCleared : TransferMutation
    data object Reset : TransferMutation
}

class TransferStateReducer {
    fun reduce(state: TransferState, mutation: TransferMutation): TransferState = when (mutation) {
        is TransferMutation.DraftSelected -> state.copy(
            draft = mutation.draft,
            validation = mutation.validation,
            phase = TransferLifecyclePhase.EDITING,
            result = null,
            selectedOperationId = null,
            failure = null,
            error = null,
        )
        TransferMutation.AuthenticationStarted -> state.copy(
            phase = TransferLifecyclePhase.AUTHENTICATING,
            isAuthenticating = true,
            isSubmitting = false,
            failure = null,
            error = null,
        )
        is TransferMutation.AuthenticationFailed -> state.copy(
            phase = TransferLifecyclePhase.EDITING,
            isAuthenticating = false,
            isSubmitting = false,
            failure = mutation.failure,
            error = mutation.failure.userMessage,
        )
        TransferMutation.SubmissionStarted -> state.copy(
            phase = TransferLifecyclePhase.SUBMITTING,
            isAuthenticating = false,
            isSubmitting = true,
            failure = null,
            error = null,
        )
        is TransferMutation.OperationSelected -> state.copy(
            selectedOperationId = mutation.operationId,
        )
        is TransferMutation.SubmissionSucceeded -> state.copy(
            phase = mutation.transfer.status.toLifecyclePhase(),
            isAuthenticating = false,
            isSubmitting = false,
            isRefreshingStatus = false,
            result = mutation.transfer,
            selectedOperationId = mutation.transfer.operationId,
            history = state.history.upsert(mutation.transfer),
            failure = null,
            error = null,
        )
        is TransferMutation.SubmissionFailed -> state.copy(
            phase = TransferLifecyclePhase.EDITING,
            isAuthenticating = false,
            isSubmitting = false,
            failure = TransferFailure(
                kind = mutation.kind,
                userMessage = mutation.message,
                canTryAgain = mutation.canTryAgain,
            ),
            error = mutation.message,
        )
        TransferMutation.StatusRefreshStarted -> state.copy(
            isRefreshingStatus = true,
            failure = null,
            error = null,
        )
        is TransferMutation.StatusRefreshFinished -> state.afterRefresh(mutation.result)
        is TransferMutation.StatusRefreshFailed -> state.copy(
            isRefreshingStatus = false,
            failure = mutation.failure,
            error = mutation.failure.userMessage,
        )
        TransferMutation.ReconciliationStarted -> state.copy(isReconciling = true)
        is TransferMutation.ReconciliationFinished -> state.copy(
            isReconciling = false,
            reconciliation = mutation.summary,
        )
        is TransferMutation.HistoryLoaded -> state.copy(history = mutation.snapshot)
        is TransferMutation.HistoryFilterChanged -> state.copy(
            history = TransferHistorySnapshot(state.history.records, mutation.filter),
        )
        TransferMutation.ErrorCleared -> state.copy(failure = null, error = null)
        TransferMutation.Reset -> TransferState(
            history = state.history,
            reconciliation = state.reconciliation,
        )
    }
}

private fun TransferState.afterRefresh(result: TransferStatusRefreshResult): TransferState = when (result) {
    is TransferStatusRefreshResult.Updated -> copy(
        phase = result.current.status.toLifecyclePhase(),
        isRefreshingStatus = false,
        result = result.current,
        selectedOperationId = result.current.operationId,
        history = history.upsert(result.current),
        failure = null,
        error = null,
    )
    is TransferStatusRefreshResult.Unchanged -> copy(
        phase = result.record.status.toLifecyclePhase(),
        isRefreshingStatus = false,
        result = result.record,
        selectedOperationId = result.record.operationId,
        history = history.upsert(result.record),
        failure = null,
        error = null,
    )
    is TransferStatusRefreshResult.NotTracked -> copy(
        isRefreshingStatus = false,
        failure = TransferFailure(
            kind = TransferFailureKind.OUTCOME_UNCONFIRMED,
            userMessage = "This operation is not available on this device",
            canTryAgain = false,
        ),
        error = "This operation is not available on this device",
    )
    is TransferStatusRefreshResult.NotFound -> copy(
        isRefreshingStatus = false,
        result = result.localRecord,
        failure = TransferFailure(
            kind = TransferFailureKind.OUTCOME_UNCONFIRMED,
            userMessage = "The bank has not returned a status for this operation",
            canTryAgain = true,
        ),
        error = "The bank has not returned a status for this operation",
    )
    is TransferStatusRefreshResult.TransitionRejected -> copy(
        isRefreshingStatus = false,
        result = result.localRecord,
        failure = TransferFailure(
            kind = TransferFailureKind.OPERATION_CONFLICT,
            userMessage = "The reported status conflicts with the saved operation",
            canTryAgain = false,
        ),
        error = "The reported status conflicts with the saved operation",
    )
    is TransferStatusRefreshResult.PayloadMismatch -> copy(
        isRefreshingStatus = false,
        result = result.localRecord,
        failure = TransferFailure(
            kind = TransferFailureKind.OPERATION_CONFLICT,
            userMessage = "The returned transfer details do not match this operation",
            canTryAgain = false,
        ),
        error = "The returned transfer details do not match this operation",
    )
}

private fun TransferHistorySnapshot.upsert(record: TransferRecord): TransferHistorySnapshot {
    val next = records.filterNot { it.operationId == record.operationId } + record
    return TransferHistorySnapshot(next, filter)
}

private fun TransferStatus.toLifecyclePhase(): TransferLifecyclePhase = when {
    isTerminal -> TransferLifecyclePhase.RESOLVED
    this == TransferStatus.SUBMITTING -> TransferLifecyclePhase.SUBMITTING
    else -> TransferLifecyclePhase.AWAITING_SERVER
}
