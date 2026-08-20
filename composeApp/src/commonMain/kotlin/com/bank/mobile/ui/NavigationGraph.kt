package com.bank.mobile.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.bank.mobile.ui.components.BankScaffold
import com.bank.mobile.ui.components.LoadingPage
import com.bank.mobile.ui.screens.AccountsScreen
import com.bank.mobile.ui.screens.BeneficiariesScreen
import com.bank.mobile.ui.screens.OperationStatusScreen
import com.bank.mobile.ui.screens.ScheduledPaymentsScreen
import com.bank.mobile.ui.screens.TransferConfirmationScreen
import com.bank.mobile.ui.screens.TransferResultScreen
import com.bank.mobile.ui.screens.TransferScreen
import androidx.compose.runtime.collectAsState
import com.bank.mobile.feature.accounts.Account
import com.bank.mobile.feature.accounts.AccountsEffect
import com.bank.mobile.feature.accounts.AccountsFailure
import com.bank.mobile.feature.beneficiaries.Beneficiary
import com.bank.mobile.feature.beneficiaries.BeneficiaryFailure
import com.bank.mobile.feature.beneficiaries.BeneficiaryValidationErrors
import com.bank.mobile.feature.scheduled.ScheduledFailure
import com.bank.mobile.feature.scheduled.ScheduledPayment
import com.bank.mobile.feature.scheduled.ScheduledStatus
import com.bank.mobile.core.lifecycle.ProcessState
import com.bank.mobile.feature.transfer.TransferAction
import kotlinx.coroutines.launch

enum class BankRoute {
    Accounts,
    Transfer,
    Beneficiaries,
    Confirmation,
    Result,
    OperationStatus,
    Scheduled,
}

@Composable
fun BankNavigation(coordinator: BankUiCoordinator) {
    val coroutineScope = rememberCoroutineScope()
    val accountsSnackbarHostState = remember { SnackbarHostState() }
    val accountState = coordinator.accountsStore?.state?.collectAsState()
    val beneficiaryState = coordinator.beneficiaryStore?.state?.collectAsState()
    val sharedScheduled = coordinator.scheduledStore?.state?.collectAsState()
    val processState = coordinator.appLifecycleObserver?.state?.collectAsState()

    LaunchedEffect(coordinator.accountsStore) {
        coordinator.accountsStore?.effects?.collect { effect ->
            when (effect) {
                is AccountsEffect.ShowMessage -> accountsSnackbarHostState.showSnackbar(
                    effect.failure.userMessage,
                )
            }
        }
    }
    LaunchedEffect(coordinator.accountsStore) {
        coordinator.accountsStore?.refresh()
    }
    LaunchedEffect(coordinator.beneficiaryStore) {
        coordinator.beneficiaryStore?.refresh()
    }
    LaunchedEffect(processState?.value) {
        if (processState?.value == ProcessState.FOREGROUND) {
            coordinator.transferViewModel?.dispatch(TransferAction.AppForegrounded)
            coordinator.scheduledStore?.runDue()
        }
    }
    coordinator.transferViewModel?.let { viewModel ->
        val sharedState by viewModel.state.collectAsState()
        LaunchedEffect(sharedState) {
            coordinator.updateFromShared(sharedState)
        }
        LaunchedEffect(viewModel) {
            viewModel.events.collect(coordinator::handle)
        }
    }
    val sharedAccountState = accountState?.value
    val accounts = if (coordinator.accountsStore == null) {
        coordinator.accounts
    } else {
        sharedAccountState?.accounts.orEmpty().map { account ->
            account.toUi(isStale = sharedAccountState?.isStale(account.id) == true)
        }
    }
    val sharedBeneficiaries = beneficiaryState?.value?.beneficiaries
    val beneficiaries = if (coordinator.beneficiaryStore == null) {
        coordinator.beneficiaries
    } else {
        sharedBeneficiaries.orEmpty().map(Beneficiary::toUi)
    }
    val scheduledPayments = if (coordinator.scheduledStore == null) {
        coordinator.scheduledPayments
    } else {
        sharedScheduled?.value?.payments.orEmpty().map { it.toUi(sharedBeneficiaries.orEmpty()) }
    }
    val route = coordinator.route
    BankScaffold(
        title = route.title,
        canNavigateBack = route != BankRoute.Accounts,
        onBack = coordinator::back,
    ) { padding ->
        val pageModifier = Modifier.padding(padding)
        when (route) {
            BankRoute.Accounts -> AccountsScreen(
                accounts = accounts,
                initialLoading = sharedAccountState?.isInitialLoading == true,
                refreshing = sharedAccountState?.refreshing == true,
                errorMessage = sharedAccountState?.failure?.userMessage,
                snackbarHostState = accountsSnackbarHostState,
                onRefresh = { coordinator.accountsStore?.refresh() },
                onTransfer = { coordinator.open(BankRoute.Transfer) },
                onScheduledPayments = { coordinator.open(BankRoute.Scheduled) },
                modifier = pageModifier,
            )

            BankRoute.Transfer -> TransferScreen(
                accounts = accounts,
                beneficiaries = beneficiaries,
                initialBeneficiaryId = coordinator.preferredBeneficiaryId,
                onReview = coordinator::review,
                onManageBeneficiaries = { coordinator.open(BankRoute.Beneficiaries) },
                modifier = pageModifier,
            )

            BankRoute.Beneficiaries -> BeneficiariesScreen(
                beneficiaries = beneficiaries,
                initialLoading = beneficiaryState?.value?.isInitialLoading == true,
                refreshing = beneficiaryState?.value?.refreshing == true,
                saving = beneficiaryState?.value?.saving == true,
                errorMessage = beneficiaryState?.value?.failure?.userMessage,
                validationErrors = beneficiaryState?.value?.validationErrors
                    ?: BeneficiaryValidationErrors(),
                savedBeneficiaryId = beneficiaryState?.value?.lastSavedBeneficiaryId,
                onRefresh = { coordinator.beneficiaryStore?.refresh() },
                onSave = { coordinator.beneficiaryStore?.save(it) },
                onSelect = coordinator::selectBeneficiary,
                onClearFeedback = { coordinator.beneficiaryStore?.clearFormFeedback() },
                modifier = pageModifier,
            )

            BankRoute.Confirmation -> coordinator.selectedDraft?.let { draft ->
                TransferConfirmationScreen(
                    draft = draft,
                    submitting = coordinator.submitting,
                    error = coordinator.submissionError,
                    onConfirm = { coroutineScope.launch { coordinator.confirmTransfer() } },
                    onEdit = { coordinator.open(BankRoute.Transfer) },
                    modifier = pageModifier,
                )
            } ?: LoadingPage("Transfer details are unavailable")

            BankRoute.Result -> coordinator.activeTransfer?.let { transfer ->
                TransferResultScreen(
                    transfer = transfer,
                    onCheckStatus = { coordinator.open(BankRoute.OperationStatus) },
                    onDone = coordinator::finishTransfer,
                    modifier = pageModifier,
                )
            } ?: LoadingPage("Transfer status is unavailable")

            BankRoute.OperationStatus -> coordinator.activeTransfer?.let { transfer ->
                OperationStatusScreen(
                    transfer = transfer,
                    refreshing = coordinator.refreshingStatus,
                    notice = coordinator.statusNotice,
                    error = coordinator.submissionError,
                    onRefresh = coordinator::refreshActiveTransfer,
                    modifier = pageModifier,
                )
            } ?: LoadingPage("Transfer status is unavailable")

            BankRoute.Scheduled -> ScheduledPaymentsScreen(
                accounts = accounts,
                beneficiaries = beneficiaries,
                payments = scheduledPayments,
                isCreating = sharedScheduled?.value?.isCreating == true,
                isRunningDue = sharedScheduled?.value?.isRunningDue == true,
                notice = sharedScheduled?.value?.notice,
                error = sharedScheduled?.value?.error,
                onSchedule = coordinator::schedulePayment,
                onRunDue = coordinator::runDuePayments,
                onDismissFeedback = coordinator::clearScheduledFeedback,
                modifier = pageModifier,
            )
        }
    }
}

private fun Account.toUi(isStale: Boolean = false): AccountUiModel = AccountUiModel(
    id = id,
    name = displayName,
    maskedNumber = maskedNumber,
    balanceMinor = balance.minorUnits,
    currency = balance.currency.value,
    isStale = isStale,
)

private val AccountsFailure.userMessage: String
    get() = when (this) {
        AccountsFailure.CACHE_UNAVAILABLE -> "Saved account data is unavailable"
        AccountsFailure.REFRESH_FAILED -> "Unable to refresh accounts"
    }

private val BeneficiaryFailure.userMessage: String
    get() = when (this) {
        BeneficiaryFailure.CACHE_UNAVAILABLE -> "Saved recipients are unavailable"
        BeneficiaryFailure.REFRESH_FAILED -> "Unable to refresh saved recipients"
        BeneficiaryFailure.ALREADY_EXISTS -> "This account is already saved"
        BeneficiaryFailure.SAVE_FAILED -> "Unable to save this recipient"
    }

private fun Beneficiary.toUi(): BeneficiaryUiModel = BeneficiaryUiModel(
    id = id,
    displayName = displayName,
    maskedAccount = maskedAccount,
    currency = currency,
)

private fun ScheduledPayment.toUi(beneficiaries: List<Beneficiary>): ScheduledPaymentUiModel = ScheduledPaymentUiModel(
    id = scheduleId,
    recipientName = beneficiaries.firstOrNull { it.id == draft.beneficiaryId }?.displayName
        ?: draft.beneficiaryId,
    amountMinor = draft.amount.minorUnits,
    currency = draft.amount.currency.value,
    localExecutionDescription = "Device timestamp: $executeAtEpochMillis",
    operationId = operationId.value,
    remoteTransferId = remoteTransferId,
    statusLabel = when (status) {
        ScheduledStatus.QUEUED -> "Queued"
        ScheduledStatus.SUBMITTING -> "Submitting"
        ScheduledStatus.OUTCOME_UNKNOWN -> "Checking status"
        ScheduledStatus.PROCESSING -> "Processing"
        ScheduledStatus.COMPLETED -> "Completed"
        ScheduledStatus.REJECTED -> "Rejected"
        ScheduledStatus.FAILED -> "Failed"
    },
    statusDescription = when (failure) {
        ScheduledFailure.NO_CONNECTION -> "No connection was available"
        ScheduledFailure.AUTHENTICATION_REQUIRED -> "Sign in again before submitting"
        ScheduledFailure.REJECTED_BY_BANK -> "The bank rejected this payment"
        ScheduledFailure.OPERATION_CONFLICT -> "The operation could not be matched safely"
        ScheduledFailure.OUTCOME_UNCONFIRMED -> "The bank outcome has not been confirmed"
        ScheduledFailure.TEMPORARY_FAILURE -> "The payment could not be submitted"
        null -> null
    },
)

private val BankRoute.title: String
    get() = when (this) {
        BankRoute.Accounts -> "Mobile Bank"
        BankRoute.Transfer -> "New transfer"
        BankRoute.Beneficiaries -> "Beneficiaries"
        BankRoute.Confirmation -> "Confirm transfer"
        BankRoute.Result -> "Transfer status"
        BankRoute.OperationStatus -> "Operation details"
        BankRoute.Scheduled -> "Scheduled payments"
    }
