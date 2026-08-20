package com.bank.mobile.ui

import androidx.compose.runtime.Immutable

@Immutable
data class AccountUiModel(
    val id: String,
    val name: String,
    val maskedNumber: String,
    val balanceMinor: Long,
    val currency: String,
    val isStale: Boolean = false,
)

@Immutable
data class BeneficiaryUiModel(
    val id: String,
    val displayName: String,
    val maskedAccount: String,
    val currency: String,
)

@Immutable
data class TransferDraftUiModel(
    val fromAccountId: String,
    val toAccountId: String,
    val recipientName: String,
    val amountMinor: Long,
    val currency: String,
    val note: String,
    val fromAccountLabel: String = fromAccountId,
)

enum class OperationStatusUi {
    SUBMITTING,
    OUTCOME_UNKNOWN,
    PROCESSING,
    COMPLETED,
    REJECTED,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == REJECTED || this == FAILED

    val canRefresh: Boolean
        get() = this == OUTCOME_UNKNOWN || this == PROCESSING

    val headline: String
        get() = when (this) {
            SUBMITTING -> "Sending transfer"
            OUTCOME_UNKNOWN -> "Checking your transfer"
            PROCESSING -> "Transfer is processing"
            COMPLETED -> "Transfer complete"
            REJECTED -> "Transfer rejected"
            FAILED -> "Transfer not submitted"
        }

    val explanation: String
        get() = when (this) {
            SUBMITTING -> "Your request is being sent to the bank."
            OUTCOME_UNKNOWN -> "A final response is not available yet."
            PROCESSING -> "The bank accepted the request and is processing it."
            COMPLETED -> "The bank reports that the transfer has completed."
            REJECTED -> "The bank did not approve this transfer."
            FAILED -> "The app could not submit this transfer."
        }
}

enum class TransferTimelineStageUi {
    CREATED,
    SENT,
    ACKNOWLEDGED,
    RESOLVED,
}

@Immutable
data class TransferTimelineUiModel(
    val stage: TransferTimelineStageUi,
    val title: String,
    val detail: String,
    val timestampLabel: String?,
    val completed: Boolean,
)

@Immutable
data class TransferResultUiModel(
    val transferId: String,
    val operationId: String,
    val draft: TransferDraftUiModel,
    val status: OperationStatusUi,
    val message: String? = null,
    val flowLabel: String = "Instant transfer",
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
    val attemptCount: Long = 0L,
    val serverStatus: OperationStatusUi? = null,
    val timeline: List<TransferTimelineUiModel> = emptyList(),
) {
    val isServerConfirmed: Boolean
        get() = serverStatus != null && serverStatus == status

    val canRefreshStatus: Boolean
        get() = status.canRefresh

    val statusSummary: String
        get() = message ?: status.explanation
}

@Immutable
data class TransferHistorySummaryUiModel(
    val total: Int,
    val inFlight: Int,
    val completed: Int,
    val unsuccessful: Int,
)

@Immutable
data class TransferFormValidationUiModel(
    val amountError: String? = null,
    val accountError: String? = null,
    val beneficiaryError: String? = null,
    val referenceError: String? = null,
) {
    val isValid: Boolean
        get() = amountError == null &&
            accountError == null &&
            beneficiaryError == null &&
            referenceError == null
}

@Immutable
data class ScheduledPaymentUiModel(
    val id: String,
    val recipientName: String,
    val amountMinor: Long,
    val currency: String,
    val localExecutionDescription: String,
    val operationId: String,
    val remoteTransferId: String?,
    val statusLabel: String,
    val statusDescription: String?,
)

internal fun Long.formatMinor(currency: String): String {
    val absolute = if (this < 0) -this else this
    val major = absolute / 100
    val minor = (absolute % 100).toString().padStart(2, '0')
    val sign = if (this < 0) "-" else ""
    return "$sign$major.$minor $currency"
}
