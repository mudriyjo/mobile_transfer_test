package com.bank.mobile.feature.scheduled

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.network.UnknownOutcomeException
import com.bank.mobile.core.time.EpochClock
import com.bank.mobile.feature.transfer.ReconcileSummary
import com.bank.mobile.feature.transfer.TransferDraft
import com.bank.mobile.feature.transfer.TransferRecord
import com.bank.mobile.feature.transfer.TransferRepository
import com.bank.mobile.feature.transfer.TransferStatus
import com.bank.mobile.feature.transfer.TransferHistoryFilter
import com.bank.mobile.feature.transfer.TransferHistorySnapshot
import com.bank.mobile.feature.transfer.TransferStatusRefreshResult
import com.bank.mobile.testing.testDatabase
import com.bank.mobile.testing.transferDraft
import com.bank.mobile.testing.transferRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduledRepositoryImplTest {
    @Test fun oneOccurrenceKeepsOneOperationIdAndPersistsResult() = runTest {
        val (database, driver) = testDatabase()
        try {
            val transfers = RecordingTransferRepository()
            val repository = ScheduledRepositoryImpl(database, transfers, EpochClock { 1_000 })
            val payment = repository.schedule("rent", transferDraft(), 500)
            val summary = repository.submitDue(1_500)

            assertEquals(payment.operationId, transfers.operationIds.single())
            assertEquals(ScheduledRunSummary(1, 1, 0, 0), summary)
            val stored = repository.observe().first().single()
            assertEquals(ScheduledStatus.COMPLETED, stored.status)
            assertEquals("remote-scheduled-rent", stored.remoteTransferId)
            assertNull(stored.failure)
            assertEquals(1_500, stored.lastAttemptAtEpochMillis)
        } finally {
            driver.close()
        }
    }

    @Test fun unknownOutcomeIsPersistedAndNotSubmittedAgain() = runTest {
        val (database, driver) = testDatabase()
        try {
            val transfers = RecordingTransferRepository(
                outcomes = ArrayDeque(
                    listOf(TransferOutcome.Failure(UnknownOutcomeException(IllegalStateException("timeout")))),
                ),
            )
            val repository = ScheduledRepositoryImpl(database, transfers, EpochClock { 2_000 })
            repository.schedule("utilities", transferDraft(), 0)

            val first = repository.submitDue(2_000)
            val second = repository.submitDue(3_000)

            assertEquals(ScheduledRunSummary(1, 0, 1, 0), first)
            assertEquals(ScheduledRunSummary(0, 0, 0, 0), second)
            assertEquals(1, transfers.operationIds.size)
            val stored = repository.observe().first().single()
            assertEquals(ScheduledStatus.OUTCOME_UNKNOWN, stored.status)
            assertEquals(ScheduledFailure.OUTCOME_UNCONFIRMED, stored.failure)
        } finally {
            driver.close()
        }
    }

    @Test fun oneFailedPaymentDoesNotBlockTheNextDuePayment() = runTest {
        val (database, driver) = testDatabase()
        try {
            val transfers = RecordingTransferRepository(
                outcomes = ArrayDeque(
                    listOf(
                        TransferOutcome.Failure(IllegalStateException("service unavailable")),
                        TransferOutcome.Success,
                    ),
                ),
            )
            val repository = ScheduledRepositoryImpl(database, transfers, EpochClock { 4_000 })
            repository.schedule("first", transferDraft(), 0)
            repository.schedule("second", transferDraft().copy(reference = "Second"), 0)

            val summary = repository.submitDue(4_000)

            assertEquals(ScheduledRunSummary(2, 1, 0, 1), summary)
            val stored = repository.observe().first()
            assertEquals(
                listOf(ScheduledStatus.FAILED, ScheduledStatus.COMPLETED),
                stored.map(ScheduledPayment::status),
            )
            assertEquals(ScheduledFailure.TEMPORARY_FAILURE, stored.first().failure)
        } finally {
            driver.close()
        }
    }
}

private sealed interface TransferOutcome {
    data object Success : TransferOutcome
    data class Failure(val error: Throwable) : TransferOutcome
}

private class RecordingTransferRepository(
    private val outcomes: ArrayDeque<TransferOutcome> = ArrayDeque(),
) : TransferRepository {
    val operationIds = mutableListOf<OperationId>()

    override suspend fun createTransfer(operationId: OperationId, draft: TransferDraft): TransferRecord {
        operationIds += operationId
        when (val outcome = outcomes.removeFirstOrNull() ?: TransferOutcome.Success) {
            is TransferOutcome.Failure -> throw outcome.error
            TransferOutcome.Success -> Unit
        }
        return transferRecord().copy(
            operationId = operationId,
            remoteTransferId = "remote-${operationId.value}",
            draft = draft,
            status = TransferStatus.COMPLETED,
            serverStatus = TransferStatus.COMPLETED,
        )
    }

    override fun observe(operationId: OperationId): Flow<TransferRecord?> = MutableStateFlow(null)
    override fun observeHistory(): Flow<List<TransferRecord>> = MutableStateFlow(emptyList())
    override suspend fun find(operationId: OperationId): TransferRecord? = null
    override suspend fun history(filter: TransferHistoryFilter): TransferHistorySnapshot =
        TransferHistorySnapshot(emptyList(), filter)
    override suspend fun refreshStatus(operationId: OperationId): TransferStatusRefreshResult =
        TransferStatusRefreshResult.NotTracked(operationId)
    override suspend fun reconcilePending(): ReconcileSummary = ReconcileSummary(0, 0, 0)
}
