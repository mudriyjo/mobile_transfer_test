package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.Money

enum class TransferStatus {
    SUBMITTING,
    OUTCOME_UNKNOWN,
    PROCESSING,
    COMPLETED,
    REJECTED,
    FAILED,

    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == REJECTED || this == FAILED

    val isInFlight: Boolean
        get() = this == SUBMITTING || this == OUTCOME_UNKNOWN || this == PROCESSING

    val canCheckServerStatus: Boolean
        get() = this == OUTCOME_UNKNOWN || this == PROCESSING

    val progressStep: Int
        get() = when (this) {
            SUBMITTING -> 1
            OUTCOME_UNKNOWN, PROCESSING -> 2
            COMPLETED, REJECTED, FAILED -> 3
        }
}

enum class TransferFlowKind { INSTANT, SCHEDULED }

enum class TransferLifecyclePhase {
    EDITING,
    AUTHENTICATING,
    SUBMITTING,
    AWAITING_SERVER,
    RESOLVED,
}

enum class TransferFailureKind {
    VALIDATION,
    AUTHENTICATION_CANCELLED,
    AUTHENTICATION_UNAVAILABLE,
    CONNECTIVITY,
    AUTHORIZATION,
    OPERATION_CONFLICT,
    BANK_REJECTION,
    OUTCOME_UNCONFIRMED,
    TEMPORARY,
}

data class TransferFailure(
    val kind: TransferFailureKind,
    val userMessage: String,
    val canTryAgain: Boolean,
    val supportReference: String? = null,
)

enum class TransferField {
    SOURCE_ACCOUNT,
    BENEFICIARY,
    AMOUNT,
    CURRENCY,
    REFERENCE,
}

sealed interface TransferValidationIssue {
    val field: TransferField

    data object SourceAccountRequired : TransferValidationIssue {
        override val field = TransferField.SOURCE_ACCOUNT
    }

    data object BeneficiaryRequired : TransferValidationIssue {
        override val field = TransferField.BENEFICIARY
    }

    data object SameSourceAndBeneficiary : TransferValidationIssue {
        override val field = TransferField.BENEFICIARY
    }

    data object AmountMustBePositive : TransferValidationIssue {
        override val field = TransferField.AMOUNT
    }

    data class AmountExceedsLimit(val limit: Money) : TransferValidationIssue {
        override val field = TransferField.AMOUNT
    }

    data class AmountExceedsAvailableBalance(val available: Money) : TransferValidationIssue {
        override val field = TransferField.AMOUNT
    }

    data class CurrencyMismatch(
        val expected: CurrencyCode,
        val actual: CurrencyCode,
    ) : TransferValidationIssue {
        override val field = TransferField.CURRENCY
    }

    data class ReferenceTooLong(val maximumLength: Int) : TransferValidationIssue {
        override val field = TransferField.REFERENCE
    }

    data object ReferenceContainsControlCharacter : TransferValidationIssue {
        override val field = TransferField.REFERENCE
    }
}

data class TransferValidationContext(
    val sourceAccountId: String? = null,
    val beneficiaryId: String? = null,
    val sourceCurrency: CurrencyCode? = null,
    val beneficiaryCurrency: CurrencyCode? = null,
    val availableBalance: Money? = null,
    val maximumAmount: Money? = null,
    val maximumReferenceLength: Int = 70,
) {
    init {
        require(maximumReferenceLength in 1..256)
        if (availableBalance != null && sourceCurrency != null) {
            require(availableBalance.currency == sourceCurrency)
        }
        if (maximumAmount != null && sourceCurrency != null) {
            require(maximumAmount.currency == sourceCurrency)
        }
    }
}

data class TransferValidationReport(
    val issues: List<TransferValidationIssue>,
) {
    val isValid: Boolean
        get() = issues.isEmpty()

    fun issuesFor(field: TransferField): List<TransferValidationIssue> =
        issues.filter { it.field == field }

    fun firstIssueFor(field: TransferField): TransferValidationIssue? =
        issues.firstOrNull { it.field == field }

    companion object {
        val Valid = TransferValidationReport(emptyList())
    }
}

data class TransferDraftInput(
    val fromAccountId: String,
    val beneficiaryId: String,
    val amount: Money?,
    val reference: String? = null,
) {
    fun toDraftOrNull(): TransferDraft? {
        val value = amount ?: return null
        if (fromAccountId.isBlank() || beneficiaryId.isBlank() || value.isZero) return null
        return TransferDraft(fromAccountId, beneficiaryId, value, reference)
    }
}

class TransferDraftValidator {
    fun validate(
        draft: TransferDraft,
        context: TransferValidationContext = TransferValidationContext(),
    ): TransferValidationReport = validate(
        input = TransferDraftInput(
            fromAccountId = draft.fromAccountId,
            beneficiaryId = draft.beneficiaryId,
            amount = draft.amount,
            reference = draft.reference,
        ),
        context = context,
    )

    fun validate(
        input: TransferDraftInput,
        context: TransferValidationContext = TransferValidationContext(),
    ): TransferValidationReport {
        val issues = buildList {
            if (input.fromAccountId.isBlank()) add(TransferValidationIssue.SourceAccountRequired)
            if (input.beneficiaryId.isBlank()) add(TransferValidationIssue.BeneficiaryRequired)
            if (input.fromAccountId.isNotBlank() && input.fromAccountId == input.beneficiaryId) {
                add(TransferValidationIssue.SameSourceAndBeneficiary)
            }
            if (input.amount == null || input.amount.isZero) {
                add(TransferValidationIssue.AmountMustBePositive)
            }

            context.sourceAccountId?.let { expected ->
                if (input.fromAccountId != expected) add(TransferValidationIssue.SourceAccountRequired)
            }
            context.beneficiaryId?.let { expected ->
                if (input.beneficiaryId != expected) add(TransferValidationIssue.BeneficiaryRequired)
            }
            input.amount?.let { amount ->
                context.sourceCurrency?.let { expected ->
                    if (amount.currency != expected) {
                        add(TransferValidationIssue.CurrencyMismatch(expected, amount.currency))
                    }
                }
                context.beneficiaryCurrency?.let { expected ->
                    if (amount.currency != expected) {
                        add(TransferValidationIssue.CurrencyMismatch(expected, amount.currency))
                    }
                }
                context.availableBalance?.let { available ->
                    if (amount.currency == available.currency && amount > available) {
                        add(TransferValidationIssue.AmountExceedsAvailableBalance(available))
                    }
                }
                context.maximumAmount?.let { limit ->
                    if (amount.currency == limit.currency && amount > limit) {
                        add(TransferValidationIssue.AmountExceedsLimit(limit))
                    }
                }
            }
            input.reference?.let { reference ->
                if (reference.length > context.maximumReferenceLength) {
                    add(TransferValidationIssue.ReferenceTooLong(context.maximumReferenceLength))
                }
                if (reference.any { it.isISOControl() }) {
                    add(TransferValidationIssue.ReferenceContainsControlCharacter)
                }
            }
        }
        return TransferValidationReport(issues.distinct())
    }
}

data class TransferDraft(
    val fromAccountId: String,
    val beneficiaryId: String,
    val amount: Money,
    val reference: String? = null,
) {
    init {
        require(fromAccountId.isNotBlank())
        require(beneficiaryId.isNotBlank())
        require(amount.minorUnits > 0)
    }

    fun fingerprint(): String = listOf(
        fromAccountId,
        beneficiaryId,
        amount.minorUnits.toString(),
        amount.currency.value,
        reference.orEmpty(),
    ).joinToString("|").fold(1L) { acc, char -> acc * 31 + char.code }.toULong().toString(16)

    fun normalized(): TransferDraft = copy(
        fromAccountId = fromAccountId.trim(),
        beneficiaryId = beneficiaryId.trim(),
        reference = reference?.trim()?.takeUnless(String::isEmpty),
    )

    fun sameEconomicPayload(other: TransferDraft): Boolean =
        fromAccountId == other.fromAccountId &&
            beneficiaryId == other.beneficiaryId &&
            amount == other.amount &&
            reference.orEmpty() == other.reference.orEmpty()
}

data class TransferRecord(
    val operationId: OperationId,
    val remoteTransferId: String?,
    val flowKind: TransferFlowKind,
    val draft: TransferDraft,
    val payloadFingerprint: String,
    val status: TransferStatus,
    val serverStatus: TransferStatus?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val attemptCount: Long,
) {
    init {
        require(payloadFingerprint.isNotBlank())
        require(createdAtEpochMillis >= 0L)
        require(updatedAtEpochMillis >= createdAtEpochMillis)
        require(attemptCount >= 0L)
        if (status == TransferStatus.COMPLETED || status == TransferStatus.REJECTED) {
            require(remoteTransferId != null) { "A server-resolved transfer requires a transfer ID" }
        }
    }

    val isResolved: Boolean
        get() = status.isTerminal

    val canRefreshStatus: Boolean
        get() = status.canCheckServerStatus

    val hasServerAgreement: Boolean
        get() = serverStatus == null || serverStatus == status

    fun ageAt(nowEpochMillis: Long): Long =
        (nowEpochMillis - createdAtEpochMillis).coerceAtLeast(0L)

    fun timeSinceUpdate(nowEpochMillis: Long): Long =
        (nowEpochMillis - updatedAtEpochMillis).coerceAtLeast(0L)

    fun matchesPayload(draft: TransferDraft): Boolean =
        payloadFingerprint == draft.fingerprint()

    fun timeline(): List<TransferTimelineEntry> = buildList {
        add(
            TransferTimelineEntry(
                stage = TransferTimelineStage.CREATED,
                atEpochMillis = createdAtEpochMillis,
                title = "Transfer created",
                detail = "The transfer details were prepared on this device.",
                completed = true,
            ),
        )
        add(
            TransferTimelineEntry(
                stage = TransferTimelineStage.SENT,
                atEpochMillis = if (attemptCount > 0L) updatedAtEpochMillis else null,
                title = "Sent to bank",
                detail = if (attemptCount > 0L) {
                    "The request has been submitted for processing."
                } else {
                    "The request has not been submitted yet."
                },
                completed = attemptCount > 0L,
            ),
        )
        add(
            TransferTimelineEntry(
                stage = TransferTimelineStage.ACKNOWLEDGED,
                atEpochMillis = remoteTransferId?.let { updatedAtEpochMillis },
                title = "Acknowledged by bank",
                detail = when {
                    remoteTransferId != null -> "The bank assigned a transfer identifier."
                    status == TransferStatus.OUTCOME_UNKNOWN -> "Acknowledgement has not been confirmed."
                    else -> "Waiting for acknowledgement."
                },
                completed = remoteTransferId != null,
            ),
        )
        val resolution = when (status) {
            TransferStatus.COMPLETED -> "The bank reports that the transfer completed."
            TransferStatus.REJECTED -> "The bank reports that the transfer was rejected."
            TransferStatus.FAILED -> "The client reports that submission failed."
            else -> "A final result is not available yet."
        }
        add(
            TransferTimelineEntry(
                stage = TransferTimelineStage.RESOLVED,
                atEpochMillis = updatedAtEpochMillis.takeIf { status.isTerminal },
                title = "Final status",
                detail = resolution,
                completed = status.isTerminal,
            ),
        )
    }
}

enum class TransferTimelineStage {
    CREATED,
    SENT,
    ACKNOWLEDGED,
    RESOLVED,
}

data class TransferTimelineEntry(
    val stage: TransferTimelineStage,
    val atEpochMillis: Long?,
    val title: String,
    val detail: String,
    val completed: Boolean,
)

enum class TransferHistoryFilter {
    ALL,
    IN_FLIGHT,
    COMPLETED,
    UNSUCCESSFUL,
    INSTANT_ONLY,
    SCHEDULED_ONLY,
    ;

    fun matches(record: TransferRecord): Boolean = when (this) {
        ALL -> true
        IN_FLIGHT -> record.status.isInFlight
        COMPLETED -> record.status == TransferStatus.COMPLETED
        UNSUCCESSFUL -> record.status == TransferStatus.REJECTED || record.status == TransferStatus.FAILED
        INSTANT_ONLY -> record.flowKind == TransferFlowKind.INSTANT
        SCHEDULED_ONLY -> record.flowKind == TransferFlowKind.SCHEDULED
    }
}

data class TransferHistorySnapshot(
    val records: List<TransferRecord>,
    val filter: TransferHistoryFilter,
) {
    val visibleRecords: List<TransferRecord> = records
        .asSequence()
        .filter(filter::matches)
        .sortedWith(
            compareByDescending<TransferRecord> { it.createdAtEpochMillis }
                .thenByDescending { it.updatedAtEpochMillis },
        )
        .toList()

    val totalCount: Int
        get() = records.size

    val inFlightCount: Int
        get() = records.count { it.status.isInFlight }

    val completedCount: Int
        get() = records.count { it.status == TransferStatus.COMPLETED }

    val unsuccessfulCount: Int
        get() = records.count {
            it.status == TransferStatus.REJECTED || it.status == TransferStatus.FAILED
        }

    fun find(operationId: OperationId): TransferRecord? =
        records.firstOrNull { it.operationId == operationId }
}

sealed interface TransferTransitionDecision {
    data object Allowed : TransferTransitionDecision
    data object Unchanged : TransferTransitionDecision
    data class Rejected(
        val from: TransferStatus,
        val to: TransferStatus,
    ) : TransferTransitionDecision
}

object TransferTransitionPolicy {
    fun evaluate(from: TransferStatus, to: TransferStatus): TransferTransitionDecision {
        if (from == to) return TransferTransitionDecision.Unchanged
        val allowed = when (from) {
            TransferStatus.SUBMITTING -> setOf(
                TransferStatus.OUTCOME_UNKNOWN,
                TransferStatus.PROCESSING,
                TransferStatus.COMPLETED,
                TransferStatus.REJECTED,
                TransferStatus.FAILED,
            )
            TransferStatus.OUTCOME_UNKNOWN -> setOf(
                TransferStatus.PROCESSING,
                TransferStatus.COMPLETED,
                TransferStatus.REJECTED,
                TransferStatus.FAILED,
            )
            TransferStatus.PROCESSING -> setOf(
                TransferStatus.OUTCOME_UNKNOWN,
                TransferStatus.COMPLETED,
                TransferStatus.REJECTED,
                TransferStatus.FAILED,
            )
            TransferStatus.COMPLETED,
            TransferStatus.REJECTED,
            TransferStatus.FAILED,
            -> emptySet()
        }
        return if (to in allowed) {
            TransferTransitionDecision.Allowed
        } else {
            TransferTransitionDecision.Rejected(from, to)
        }
    }
}
