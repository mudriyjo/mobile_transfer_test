package com.bank.backend

import kotlinx.serialization.Serializable

@Serializable
data class AccountDto(
    val id: String,
    val displayName: String,
    val balanceMinorUnits: Long,
    val currency: String,
    val updatedAt: String,
)

@Serializable
data class BeneficiaryDto(
    val id: String,
    val displayName: String,
    val maskedAccount: String,
    val currency: String,
)

@Serializable
data class CreateBeneficiaryRequest(
    val displayName: String,
    val accountIdentifier: String,
    val currency: String,
)

@Serializable
data class CreateTransferRequest(
    val fromAccountId: String,
    val toAccountId: String,
    val amountMinorUnits: Long,
    val currency: String,
    val reference: String? = null,
)

@Serializable
enum class TransferStatus {
    PROCESSING,
    COMPLETED,
    REJECTED,
}

@Serializable
data class TransferDto(
    val transferId: String,
    val operationId: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amountMinorUnits: Long,
    val currency: String,
    val reference: String? = null,
    val status: TransferStatus,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val retriable: Boolean = false,
    val outcome: OperationOutcome = OperationOutcome.NOT_APPLICABLE,
)

@Serializable
enum class OperationOutcome {
    NOT_APPLICABLE,
    NOT_COMMITTED,
    UNKNOWN_TO_CLIENT,
}

@Serializable
data class HealthResponse(
    val status: String,
)

@Serializable
enum class SubmitFaultMode {
    NORMAL,
    REJECT_BEFORE_COMMIT,
    COMMIT_THEN_TIMEOUT,
    COMMIT_THEN_MALFORMED_RESPONSE,
    BLOCK_AFTER_COMMIT,
}

@Serializable
data class FaultPlan(
    val submitMode: SubmitFaultMode = SubmitFaultMode.NORMAL,
    val submitModeApplications: Int = 1,
    val submitDelayMillis: Long = 10_000,
    val statusFailuresBeforeSuccess: Int = 0,
    val completeAfterSuccessfulStatusChecks: Int = 1,
    val terminalStatus: TransferStatus = TransferStatus.COMPLETED,
)

@Serializable
data class FaultStateDto(
    val plan: FaultPlan,
    val submitModeApplicationsRemaining: Int,
    val blockedSubmissions: Int,
)

@Serializable
enum class JournalEventType {
    TRANSFER_COMMITTED,
    IDEMPOTENT_REPLAY,
    IDEMPOTENCY_CONFLICT,
    STATUS_CHANGED,
}

@Serializable
data class JournalEntryDto(
    val sequence: Long,
    val type: JournalEventType,
    val operationId: String,
    val transferId: String? = null,
    val status: TransferStatus? = null,
    val recordedAt: String,
)

@Serializable
data class ResetResponse(
    val reset: Boolean,
)

@Serializable
data class ReleaseResponse(
    val releasedSubmissions: Int,
)

internal fun CreateTransferRequest.validationError(directory: BankDirectory): String? = when {
    fromAccountId.isBlank() -> "fromAccountId is required"
    toAccountId.isBlank() -> "toAccountId is required"
    fromAccountId == toAccountId -> "Source and destination accounts must differ"
    amountMinorUnits <= 0 -> "amountMinorUnits must be positive"
    amountMinorUnits > 100_000_000_00L -> "Transfer amount exceeds the stub limit"
    currency.length != 3 || currency.any { !it.isUpperCase() } -> "currency must be a three-letter uppercase code"
    reference != null && reference.length > 140 -> "reference must not exceed 140 characters"
    directory.account(fromAccountId) == null -> "Unknown source account"
    directory.destinationAccount(toAccountId) == null -> "Unknown destination account"
    directory.account(fromAccountId)?.currency != currency -> "Source account currency does not match"
    directory.destinationAccount(toAccountId)?.currency != currency -> "Destination account currency does not match"
    else -> null
}

internal fun CreateBeneficiaryRequest.normalized(): CreateBeneficiaryRequest = copy(
    displayName = displayName.trim().replace(Regex("\\s+"), " "),
    accountIdentifier = accountIdentifier.filterNot(Char::isWhitespace).uppercase(),
    currency = currency.trim().uppercase(),
)

internal fun CreateBeneficiaryRequest.validationError(): String? = when {
    displayName.length !in 2..60 -> "displayName must contain 2 to 60 characters"
    displayName.any(Char::isISOControl) -> "displayName must not contain control characters"
    displayName.none(Char::isLetter) -> "displayName must contain a letter"
    accountIdentifier.length !in 8..34 -> "accountIdentifier must contain 8 to 34 characters"
    accountIdentifier.any { !it.isLetterOrDigit() } -> "accountIdentifier must contain only letters and digits"
    currency.length != 3 || currency.any { !it.isLetter() || !it.isUpperCase() } ->
        "currency must be a three-letter uppercase code"
    else -> null
}
