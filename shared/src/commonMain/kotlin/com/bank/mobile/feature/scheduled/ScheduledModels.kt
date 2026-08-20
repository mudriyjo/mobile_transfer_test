package com.bank.mobile.feature.scheduled

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.feature.transfer.TransferDraft

enum class ScheduledStatus {
    QUEUED,
    SUBMITTING,
    OUTCOME_UNKNOWN,
    PROCESSING,
    COMPLETED,
    REJECTED,
    FAILED,
}

enum class ScheduledFailure {
    NO_CONNECTION,
    AUTHENTICATION_REQUIRED,
    REJECTED_BY_BANK,
    OPERATION_CONFLICT,
    OUTCOME_UNCONFIRMED,
    TEMPORARY_FAILURE,
}

data class ScheduledPayment(
    val scheduleId: String,
    val draft: TransferDraft,
    val executeAtEpochMillis: Long,
    val operationId: OperationId,
    val status: ScheduledStatus,
    val remoteTransferId: String? = null,
    val failure: ScheduledFailure? = null,
    val lastAttemptAtEpochMillis: Long? = null,
)

data class ScheduledRunSummary(
    val due: Int,
    val submitted: Int,
    val unresolved: Int,
    val failed: Int,
)
