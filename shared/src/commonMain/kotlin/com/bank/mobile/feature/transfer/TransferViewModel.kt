package com.bank.mobile.feature.transfer

import com.bank.mobile.core.analytics.AnalyticsEvent
import com.bank.mobile.core.analytics.AnalyticsTracker
import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.ids.OperationIdProvider
import com.bank.mobile.core.network.AuthenticationException
import com.bank.mobile.core.network.DefinitiveRejectionException
import com.bank.mobile.core.network.IdempotencyConflictException
import com.bank.mobile.core.network.NoInternetException
import com.bank.mobile.core.network.UnknownOutcomeException
import com.bank.mobile.core.security.BiometricAuthenticator
import com.bank.mobile.core.security.BiometricResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TransferViewModel(
    private val createTransfer: CreateTransferUseCase,
    private val reconcileTransfer: ReconcileTransferUseCase,
    private val operationIds: OperationIdProvider,
    private val biometricAuthenticator: BiometricAuthenticator,
    private val analytics: AnalyticsTracker,
    private val scope: CoroutineScope,
    private val reducer: TransferStateReducer = TransferStateReducer(),
) {
    private val mutableState = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<TransferEvent>(replay = 1)
    val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()

    fun dispatch(action: TransferAction) {
        when (action) {
            is TransferAction.Confirm -> confirm(action.draft)
            TransferAction.Retry -> mutableState.value.draft?.let(::confirm)
            TransferAction.ClearError -> mutate(TransferMutation.ErrorCleared)
            TransferAction.AppForegrounded -> reconcileAfterForeground()
            is TransferAction.OpenOperation -> openOperation(action.operationId)
            TransferAction.RefreshCurrentStatus -> refreshCurrentStatus()
            TransferAction.LoadHistory -> loadHistory()
            is TransferAction.ChangeHistoryFilter -> changeHistoryFilter(action.filter)
            TransferAction.StartAnotherTransfer -> mutate(TransferMutation.Reset)
        }
    }

    private fun reconcileAfterForeground() {
        scope.launch {
            mutate(TransferMutation.ReconciliationStarted)
            val summary = try {
                reconcileTransfer()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                ReconcileSummary(checked = 0, updated = 0, unresolved = 0)
            }
            mutate(TransferMutation.ReconciliationFinished(summary))
            refreshSelectedFromLocal()
            loadHistorySnapshot(mutableState.value.history.filter)
        }
    }

    private fun openOperation(operationId: OperationId) {
        mutate(TransferMutation.OperationSelected(operationId))
        scope.launch {
            val transfer = try {
                reconcileTransfer(operationId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            transfer ?: return@launch
            mutate(TransferMutation.SubmissionSucceeded(transfer))
            mutableEvents.emit(TransferEvent.OpenResult(transfer.operationId.value))
            loadHistorySnapshot(mutableState.value.history.filter)
        }
    }

    private fun confirm(draft: TransferDraft) {
        if (mutableState.value.isSubmitting || mutableState.value.isAuthenticating) return
        val validation = createTransfer.validate(draft)
        mutate(TransferMutation.DraftSelected(draft, validation))
        if (!validation.isValid) {
            mutate(
                TransferMutation.SubmissionFailed(
                    message = "Check the transfer details and try again",
                    kind = TransferFailureKind.VALIDATION,
                    canTryAgain = false,
                ),
            )
            return
        }
        mutate(TransferMutation.AuthenticationStarted)

        scope.launch {
            val biometric = biometricAuthenticator.authenticate("Confirm bank transfer")
            if (biometric != BiometricResult.Success && biometric != BiometricResult.LockedOut) {
                mutate(TransferMutation.AuthenticationFailed(biometric.toFailure()))
                return@launch
            }

            mutate(TransferMutation.SubmissionStarted)
            val operationId = operationIds.next()
            mutate(TransferMutation.OperationSelected(operationId))
            analytics.track(
                AnalyticsEvent(
                    name = "transfer_confirmed",
                    attributes = mapOf(
                        "from_account" to draft.fromAccountId,
                        "beneficiary" to draft.beneficiaryId,
                        "amount" to draft.amount.minorUnits.toString(),
                        "currency" to draft.amount.currency.value,
                    ),
                ),
            )

            runCatching { createTransfer(operationId, draft) }
                .onSuccess { transfer ->
                    mutate(TransferMutation.SubmissionSucceeded(transfer))
                    mutableEvents.emit(TransferEvent.OpenResult(transfer.operationId.value))
                    loadHistorySnapshot(mutableState.value.history.filter)
                }
                .onFailure { error ->
                    val failure = error.toTransferFailure()
                    mutate(
                        TransferMutation.SubmissionFailed(
                            message = failure.userMessage,
                            kind = failure.kind,
                            canTryAgain = failure.canTryAgain,
                        ),
                    )
                }
        }
    }

    private fun refreshCurrentStatus() {
        val operationId = mutableState.value.result?.operationId
            ?: mutableState.value.selectedOperationId
            ?: return
        if (mutableState.value.isRefreshingStatus) return

        scope.launch {
            mutate(TransferMutation.StatusRefreshStarted)
            val result = try {
                reconcileTransfer.refresh(operationId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                mutate(TransferMutation.StatusRefreshFailed(error.toTransferFailure()))
                return@launch
            }
            mutate(TransferMutation.StatusRefreshFinished(result))
            loadHistorySnapshot(mutableState.value.history.filter)
            when (result) {
                is TransferStatusRefreshResult.Updated -> mutableEvents.emit(
                    TransferEvent.ShowNotice("Transfer status updated"),
                )
                is TransferStatusRefreshResult.Unchanged -> mutableEvents.emit(
                    TransferEvent.ShowNotice("No status change yet"),
                )
                is TransferStatusRefreshResult.NotFound -> mutableEvents.emit(
                    TransferEvent.ShowNotice("The bank has not returned a status yet"),
                )
                is TransferStatusRefreshResult.NotTracked,
                is TransferStatusRefreshResult.PayloadMismatch,
                is TransferStatusRefreshResult.TransitionRejected,
                -> Unit
            }
        }
    }

    private fun loadHistory() {
        scope.launch { loadHistorySnapshot(mutableState.value.history.filter) }
    }

    private fun changeHistoryFilter(filter: TransferHistoryFilter) {
        mutate(TransferMutation.HistoryFilterChanged(filter))
        scope.launch { loadHistorySnapshot(filter) }
    }

    private suspend fun loadHistorySnapshot(filter: TransferHistoryFilter) {
        val snapshot = try {
            reconcileTransfer.history(filter)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return
        }
        mutate(TransferMutation.HistoryLoaded(snapshot))
    }

    private suspend fun refreshSelectedFromLocal() {
        val operationId = mutableState.value.selectedOperationId ?: return
        val record = reconcileTransfer.find(operationId) ?: return
        mutate(TransferMutation.SubmissionSucceeded(record))
    }

    fun close() {
        scope.cancel()
    }

    private fun mutate(mutation: TransferMutation) {
        mutableState.value = reducer.reduce(mutableState.value, mutation)
    }
}

private fun BiometricResult.toFailure(): TransferFailure = when (this) {
    BiometricResult.Cancelled -> TransferFailure(
        kind = TransferFailureKind.AUTHENTICATION_CANCELLED,
        userMessage = "Authentication was cancelled",
        canTryAgain = true,
    )
    is BiometricResult.Failure -> TransferFailure(
        kind = TransferFailureKind.AUTHENTICATION_UNAVAILABLE,
        userMessage = message.ifBlank { "Device authentication is unavailable" },
        canTryAgain = true,
    )
    BiometricResult.LockedOut -> TransferFailure(
        kind = TransferFailureKind.AUTHENTICATION_UNAVAILABLE,
        userMessage = "Device authentication is temporarily locked",
        canTryAgain = false,
    )
    BiometricResult.Success -> error("A successful authentication has no failure")
}

private fun Throwable.toTransferFailure(): TransferFailure = when (this) {
    is NoInternetException -> TransferFailure(
        kind = TransferFailureKind.CONNECTIVITY,
        userMessage = message ?: "No internet connection",
        canTryAgain = true,
    )
    is AuthenticationException -> TransferFailure(
        kind = TransferFailureKind.AUTHORIZATION,
        userMessage = message ?: "Authentication is required",
        canTryAgain = false,
    )
    is IdempotencyConflictException -> TransferFailure(
        kind = TransferFailureKind.OPERATION_CONFLICT,
        userMessage = message ?: "The operation details conflict with an earlier request",
        canTryAgain = false,
    )
    is DefinitiveRejectionException -> TransferFailure(
        kind = TransferFailureKind.BANK_REJECTION,
        userMessage = message ?: "The transfer was rejected",
        canTryAgain = false,
    )
    is UnknownOutcomeException -> TransferFailure(
        kind = TransferFailureKind.OUTCOME_UNCONFIRMED,
        userMessage = message ?: "The transfer outcome has not been confirmed",
        canTryAgain = true,
    )
    else -> TransferFailure(
        kind = TransferFailureKind.TEMPORARY,
        userMessage = message ?: "Transfer failed",
        canTryAgain = true,
    )
}
