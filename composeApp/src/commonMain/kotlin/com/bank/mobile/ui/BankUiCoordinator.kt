package com.bank.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.Money
import com.bank.mobile.core.lifecycle.AppLifecycleObserver
import com.bank.mobile.feature.accounts.AccountsStore
import com.bank.mobile.feature.beneficiaries.BeneficiaryStore
import com.bank.mobile.feature.scheduled.ScheduledStore
import com.bank.mobile.feature.transfer.TransferAction
import com.bank.mobile.feature.transfer.TransferDraft
import com.bank.mobile.feature.transfer.TransferEvent
import com.bank.mobile.feature.transfer.TransferRecord
import com.bank.mobile.feature.transfer.TransferState
import com.bank.mobile.feature.transfer.TransferStatus
import com.bank.mobile.feature.transfer.TransferTimelineStage
import com.bank.mobile.feature.transfer.TransferViewModel
import kotlinx.coroutines.delay

/**
 * UI-facing boundary. Production feature state remains in the shared module; this class only
 * coordinates destinations and transient form state. The in-memory defaults are used by previews
 * and UI tests; Android and iOS application roots supply the complete shared feature graph.
 */
class BankUiCoordinator(
    initialRoute: BankRoute = BankRoute.Accounts,
    val transferViewModel: TransferViewModel? = null,
    val accountsStore: AccountsStore? = null,
    val scheduledStore: ScheduledStore? = null,
    val beneficiaryStore: BeneficiaryStore? = null,
    val appLifecycleObserver: AppLifecycleObserver? = null,
) {
    var route: BankRoute by mutableStateOf(initialRoute)
        private set

    var selectedDraft: TransferDraftUiModel? by mutableStateOf(null)
        private set

    var preferredBeneficiaryId: String? by mutableStateOf(null)
        private set

    var activeTransfer: TransferResultUiModel? by mutableStateOf(null)
        private set

    var submitting: Boolean by mutableStateOf(false)
        private set

    var submissionError: String? by mutableStateOf(null)
        private set

    var statusNotice: String? by mutableStateOf(null)
        private set

    var refreshingStatus: Boolean by mutableStateOf(false)
        private set

    var historySummary: TransferHistorySummaryUiModel by mutableStateOf(
        TransferHistorySummaryUiModel(total = 0, inFlight = 0, completed = 0, unsuccessful = 0),
    )
        private set

    val accounts = mutableStateListOf(
        AccountUiModel("account-everyday", "Everyday account", "•••• 1042", 248_130, "EUR"),
        AccountUiModel("account-savings", "Savings", "•••• 8840", 1_250_000, "EUR", isStale = true),
    )

    val beneficiaries = mutableStateListOf(
        BeneficiaryUiModel("beneficiary-alex", "Alex Morgan", "•••• 9031", "EUR"),
        BeneficiaryUiModel("beneficiary-river", "River Utilities", "•••• 4408", "EUR"),
    )

    val scheduledPayments = mutableStateListOf(
        ScheduledPaymentUiModel(
            id = "schedule-rent",
            recipientName = "Alex Morgan",
            amountMinor = 95_000,
            currency = "EUR",
            localExecutionDescription = "Monthly on day 1 at 09:00",
            operationId = "scheduled-preview",
            remoteTransferId = null,
            statusLabel = "Queued",
            statusDescription = null,
        ),
    )

    fun open(route: BankRoute) {
        this.route = route
    }

    fun review(draft: TransferDraftUiModel) {
        selectedDraft = draft
        submissionError = null
        route = BankRoute.Confirmation
    }

    fun selectBeneficiary(beneficiaryId: String) {
        preferredBeneficiaryId = beneficiaryId
        route = BankRoute.Transfer
    }

    suspend fun confirmTransfer() {
        val draft = selectedDraft ?: return
        if (submitting) return
        transferViewModel?.let { viewModel ->
            viewModel.dispatch(TransferAction.Confirm(draft.toDomain()))
            return
        }
        submitting = true
        submissionError = null

        // Preview fallback used when no feature graph is supplied.
        delay(350)
        val token = (draft.fromAccountId + draft.toAccountId + draft.amountMinor).hashCode().toUInt().toString(16)
        activeTransfer = TransferResultUiModel(
            transferId = "tr-$token",
            operationId = "op-$token",
            draft = draft,
            status = OperationStatusUi.PROCESSING,
        )
        submitting = false
        route = BankRoute.Result
    }

    fun updateFromShared(state: TransferState) {
        submitting = state.isAuthenticating || state.isSubmitting
        refreshingStatus = state.isRefreshingStatus || state.isReconciling
        submissionError = state.error
        state.result?.let { activeTransfer = it.toUi(selectedDraft) }
        historySummary = TransferHistorySummaryUiModel(
            total = state.history.totalCount,
            inFlight = state.history.inFlightCount,
            completed = state.history.completedCount,
            unsuccessful = state.history.unsuccessfulCount,
        )
    }

    fun handle(event: TransferEvent) {
        when (event) {
            is TransferEvent.OpenResult -> {
                activeTransfer = transferViewModel?.state?.value?.result?.toUi(selectedDraft)
                    ?: activeTransfer?.copy(operationId = event.operationId)
                route = BankRoute.Result
            }
            is TransferEvent.ShowNotice -> statusNotice = event.message
        }
    }

    fun showSubmissionError(message: String) {
        submitting = false
        submissionError = message
    }

    fun schedulePayment(draft: TransferDraftUiModel, delayMillis: Long) {
        scheduledStore?.schedule(draft.toDomain(), delayMillis)
    }

    fun runDuePayments() {
        scheduledStore?.runDue()
    }

    fun clearScheduledFeedback() {
        scheduledStore?.clearFeedback()
    }

    fun refreshActiveTransfer() {
        statusNotice = null
        transferViewModel?.dispatch(TransferAction.RefreshCurrentStatus)
    }

    fun clearStatusNotice() {
        statusNotice = null
    }

    fun finishTransfer() {
        selectedDraft = null
        activeTransfer = null
        submissionError = null
        statusNotice = null
        transferViewModel?.dispatch(TransferAction.StartAnotherTransfer)
        route = BankRoute.Accounts
    }

    fun back() {
        route = when (route) {
            BankRoute.Accounts -> BankRoute.Accounts
            BankRoute.Transfer -> BankRoute.Accounts
            BankRoute.Beneficiaries -> BankRoute.Transfer
            BankRoute.Confirmation -> BankRoute.Transfer
            BankRoute.Result, BankRoute.OperationStatus -> BankRoute.Accounts
            BankRoute.Scheduled -> BankRoute.Accounts
        }
    }

    fun close() {
        transferViewModel?.close()
    }
}

private fun TransferDraftUiModel.toDomain(): TransferDraft = TransferDraft(
    fromAccountId = fromAccountId,
    beneficiaryId = toAccountId,
    amount = Money(amountMinor, CurrencyCode(currency)),
    reference = note.ifBlank { null },
)

private fun TransferRecord.toUi(selectedDraft: TransferDraftUiModel?): TransferResultUiModel = TransferResultUiModel(
    transferId = remoteTransferId ?: "Pending",
    operationId = operationId.value,
    draft = TransferDraftUiModel(
        fromAccountId = draft.fromAccountId,
        fromAccountLabel = selectedDraft
            ?.takeIf { it.fromAccountId == draft.fromAccountId }
            ?.fromAccountLabel
            ?: draft.fromAccountId,
        toAccountId = draft.beneficiaryId,
        recipientName = selectedDraft
            ?.takeIf { it.toAccountId == draft.beneficiaryId }
            ?.recipientName
            ?: draft.beneficiaryId,
        amountMinor = draft.amount.minorUnits,
        currency = draft.amount.currency.value,
        note = draft.reference.orEmpty(),
    ),
    status = when (status) {
        TransferStatus.SUBMITTING -> OperationStatusUi.SUBMITTING
        TransferStatus.OUTCOME_UNKNOWN -> OperationStatusUi.OUTCOME_UNKNOWN
        TransferStatus.PROCESSING -> OperationStatusUi.PROCESSING
        TransferStatus.COMPLETED -> OperationStatusUi.COMPLETED
        TransferStatus.REJECTED -> OperationStatusUi.REJECTED
        TransferStatus.FAILED -> OperationStatusUi.FAILED
    },
    flowLabel = when (flowKind) {
        com.bank.mobile.feature.transfer.TransferFlowKind.INSTANT -> "Instant transfer"
        com.bank.mobile.feature.transfer.TransferFlowKind.SCHEDULED -> "Scheduled payment"
    },
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    attemptCount = attemptCount,
    serverStatus = serverStatus?.toUiStatus(),
    timeline = timeline().map { entry ->
        TransferTimelineUiModel(
            stage = when (entry.stage) {
                TransferTimelineStage.CREATED -> TransferTimelineStageUi.CREATED
                TransferTimelineStage.SENT -> TransferTimelineStageUi.SENT
                TransferTimelineStage.ACKNOWLEDGED -> TransferTimelineStageUi.ACKNOWLEDGED
                TransferTimelineStage.RESOLVED -> TransferTimelineStageUi.RESOLVED
            },
            title = entry.title,
            detail = entry.detail,
            timestampLabel = entry.atEpochMillis?.let { "Device time $it" },
            completed = entry.completed,
        )
    },
)

private fun TransferStatus.toUiStatus(): OperationStatusUi = when (this) {
    TransferStatus.SUBMITTING -> OperationStatusUi.SUBMITTING
    TransferStatus.OUTCOME_UNKNOWN -> OperationStatusUi.OUTCOME_UNKNOWN
    TransferStatus.PROCESSING -> OperationStatusUi.PROCESSING
    TransferStatus.COMPLETED -> OperationStatusUi.COMPLETED
    TransferStatus.REJECTED -> OperationStatusUi.REJECTED
    TransferStatus.FAILED -> OperationStatusUi.FAILED
}
