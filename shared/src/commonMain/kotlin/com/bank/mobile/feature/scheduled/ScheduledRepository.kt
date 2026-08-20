package com.bank.mobile.feature.scheduled

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.Money
import com.bank.mobile.core.network.AuthenticationException
import com.bank.mobile.core.network.DefinitiveRejectionException
import com.bank.mobile.core.network.IdempotencyConflictException
import com.bank.mobile.core.network.NoInternetException
import com.bank.mobile.core.network.UnknownOutcomeException
import com.bank.mobile.core.time.DeviceEpochClock
import com.bank.mobile.core.time.EpochClock
import com.bank.mobile.db.MobileBankDatabase
import com.bank.mobile.feature.transfer.TransferDraft
import com.bank.mobile.feature.transfer.TransferRecord
import com.bank.mobile.feature.transfer.TransferRepository
import com.bank.mobile.feature.transfer.TransferStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ScheduledRepository {
    fun observe(): Flow<List<ScheduledPayment>>
    suspend fun schedule(scheduleId: String, draft: TransferDraft, delayFromDeviceMillis: Long): ScheduledPayment
    suspend fun submitDue(nowEpochMillis: Long): ScheduledRunSummary
}

class ScheduledRepositoryImpl(
    private val database: MobileBankDatabase,
    private val transfers: TransferRepository,
    private val deviceClock: EpochClock = DeviceEpochClock,
) : ScheduledRepository {
    private val submitMutex = Mutex()

    override fun observe(): Flow<List<ScheduledPayment>> =
        database.mobileBankQueries.selectScheduledPayments().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row ->
                ScheduledPayment(
                    scheduleId = row.schedule_id,
                    draft = TransferDraft(
                        fromAccountId = row.from_account_id,
                        beneficiaryId = row.beneficiary_id,
                        amount = Money(row.amount_minor, CurrencyCode(row.currency)),
                    ),
                    executeAtEpochMillis = row.execute_at_epoch_ms,
                    operationId = OperationId(row.operation_id),
                    status = ScheduledStatus.valueOf(row.status),
                    remoteTransferId = row.remote_transfer_id,
                    failure = row.last_error_code?.let(ScheduledFailure::valueOf),
                    lastAttemptAtEpochMillis = row.last_attempt_epoch_ms,
                )
            }
        }

    override suspend fun schedule(
        scheduleId: String,
        draft: TransferDraft,
        delayFromDeviceMillis: Long,
    ): ScheduledPayment {
        require(scheduleId.isNotBlank()) { "Schedule ID cannot be blank" }
        require(delayFromDeviceMillis >= 0) { "Schedule delay cannot be negative" }
        val now = deviceClock.nowMillis()
        require(delayFromDeviceMillis <= Long.MAX_VALUE - now) { "Schedule time is out of range" }
        val executeAt = now + delayFromDeviceMillis
        val operationId = OperationId("scheduled-$scheduleId")
        database.mobileBankQueries.insertScheduledPayment(
            schedule_id = scheduleId,
            beneficiary_id = draft.beneficiaryId,
            from_account_id = draft.fromAccountId,
            amount_minor = draft.amount.minorUnits,
            currency = draft.amount.currency.value,
            execute_at_epoch_ms = executeAt,
            operation_id = operationId.value,
            status = ScheduledStatus.QUEUED.name,
        )
        return ScheduledPayment(scheduleId, draft, executeAt, operationId, ScheduledStatus.QUEUED)
    }

    override suspend fun submitDue(nowEpochMillis: Long): ScheduledRunSummary = submitMutex.withLock {
        val due = database.mobileBankQueries.selectScheduledPayments().executeAsList()
            .filter { it.execute_at_epoch_ms <= nowEpochMillis && it.status == ScheduledStatus.QUEUED.name }
        var submitted = 0
        var unresolved = 0
        var failed = 0

        due.forEach { row ->
            updateResult(
                scheduleId = row.schedule_id,
                status = ScheduledStatus.SUBMITTING,
                attemptAtEpochMillis = nowEpochMillis,
            )
            val draft = TransferDraft(
                row.from_account_id,
                row.beneficiary_id,
                Money(row.amount_minor, CurrencyCode(row.currency)),
            )
            try {
                val transfer = transfers.createTransfer(OperationId(row.operation_id), draft)
                val status = transfer.toScheduledStatus()
                updateResult(
                    scheduleId = row.schedule_id,
                    status = status,
                    remoteTransferId = transfer.remoteTransferId,
                    failure = if (status == ScheduledStatus.OUTCOME_UNKNOWN) {
                        ScheduledFailure.OUTCOME_UNCONFIRMED
                    } else {
                        null
                    },
                    attemptAtEpochMillis = nowEpochMillis,
                )
                when (status) {
                    ScheduledStatus.OUTCOME_UNKNOWN -> unresolved += 1
                    ScheduledStatus.REJECTED, ScheduledStatus.FAILED -> failed += 1
                    else -> submitted += 1
                }
            } catch (error: CancellationException) {
                updateResult(
                    scheduleId = row.schedule_id,
                    status = ScheduledStatus.OUTCOME_UNKNOWN,
                    failure = ScheduledFailure.OUTCOME_UNCONFIRMED,
                    attemptAtEpochMillis = nowEpochMillis,
                )
                throw error
            } catch (error: Exception) {
                val outcome = error.toScheduledFailure()
                updateResult(
                    scheduleId = row.schedule_id,
                    status = outcome.first,
                    failure = outcome.second,
                    attemptAtEpochMillis = nowEpochMillis,
                )
                if (outcome.first == ScheduledStatus.OUTCOME_UNKNOWN) unresolved += 1 else failed += 1
            }
        }

        ScheduledRunSummary(
            due = due.size,
            submitted = submitted,
            unresolved = unresolved,
            failed = failed,
        )
    }

    private fun updateResult(
        scheduleId: String,
        status: ScheduledStatus,
        remoteTransferId: String? = null,
        failure: ScheduledFailure? = null,
        attemptAtEpochMillis: Long,
    ) {
        database.mobileBankQueries.updateScheduledResult(
            status = status.name,
            remote_transfer_id = remoteTransferId,
            last_error_code = failure?.name,
            last_attempt_epoch_ms = attemptAtEpochMillis,
            schedule_id = scheduleId,
        )
    }
}

private fun TransferRecord.toScheduledStatus(): ScheduledStatus = when (status) {
    TransferStatus.SUBMITTING -> ScheduledStatus.SUBMITTING
    TransferStatus.OUTCOME_UNKNOWN -> ScheduledStatus.OUTCOME_UNKNOWN
    TransferStatus.PROCESSING -> ScheduledStatus.PROCESSING
    TransferStatus.COMPLETED -> ScheduledStatus.COMPLETED
    TransferStatus.REJECTED -> ScheduledStatus.REJECTED
    TransferStatus.FAILED -> ScheduledStatus.FAILED
}

private fun Exception.toScheduledFailure(): Pair<ScheduledStatus, ScheduledFailure> = when (this) {
    is UnknownOutcomeException -> ScheduledStatus.OUTCOME_UNKNOWN to ScheduledFailure.OUTCOME_UNCONFIRMED
    is NoInternetException -> ScheduledStatus.FAILED to ScheduledFailure.NO_CONNECTION
    is AuthenticationException -> ScheduledStatus.FAILED to ScheduledFailure.AUTHENTICATION_REQUIRED
    is DefinitiveRejectionException -> ScheduledStatus.REJECTED to ScheduledFailure.REJECTED_BY_BANK
    is IdempotencyConflictException -> ScheduledStatus.FAILED to ScheduledFailure.OPERATION_CONFLICT
    else -> ScheduledStatus.FAILED to ScheduledFailure.TEMPORARY_FAILURE
}
