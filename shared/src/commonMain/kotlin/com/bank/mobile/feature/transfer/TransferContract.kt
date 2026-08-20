package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId

data class TransferState(
    val draft: TransferDraft? = null,
    val validation: TransferValidationReport = TransferValidationReport.Valid,
    val phase: TransferLifecyclePhase = TransferLifecyclePhase.EDITING,
    val isAuthenticating: Boolean = false,
    val isSubmitting: Boolean = false,
    val isRefreshingStatus: Boolean = false,
    val isReconciling: Boolean = false,
    val result: TransferRecord? = null,
    val selectedOperationId: OperationId? = null,
    val history: TransferHistorySnapshot = TransferHistorySnapshot(
        records = emptyList(),
        filter = TransferHistoryFilter.ALL,
    ),
    val reconciliation: ReconcileSummary? = null,
    val failure: TransferFailure? = null,
    val error: String? = null,
) {
    val isBusy: Boolean
        get() = isAuthenticating || isSubmitting || isRefreshingStatus || isReconciling

    val canConfirm: Boolean
        get() = draft != null && validation.isValid && !isBusy

    val canRetry: Boolean
        get() = draft != null && failure?.canTryAgain == true && !isBusy

    val canRefreshStatus: Boolean
        get() = result?.canRefreshStatus == true && !isRefreshingStatus

    val hasUnresolvedOperations: Boolean
        get() = history.inFlightCount > 0

    val visibleHistory: List<TransferRecord>
        get() = history.visibleRecords
}

sealed interface TransferAction {
    data class Confirm(val draft: TransferDraft) : TransferAction
    data object Retry : TransferAction
    data object ClearError : TransferAction
    data object AppForegrounded : TransferAction
    data class OpenOperation(val operationId: OperationId) : TransferAction
    data object RefreshCurrentStatus : TransferAction
    data object LoadHistory : TransferAction
    data class ChangeHistoryFilter(val filter: TransferHistoryFilter) : TransferAction
    data object StartAnotherTransfer : TransferAction
}

sealed interface TransferEvent {
    data class OpenResult(val operationId: String) : TransferEvent
    data class ShowNotice(val message: String) : TransferEvent
}
