package com.bank.mobile.feature.transfer

import com.bank.mobile.testing.transferDraft
import com.bank.mobile.testing.transferRecord
import com.bank.mobile.core.ids.OperationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferStateReducerTest {
    private val reducer = TransferStateReducer()

    @Test fun submittingClearsAuthenticationAndError() {
        val state = reducer.reduce(
            TransferState(isAuthenticating = true, error = "old"),
            TransferMutation.SubmissionStarted,
        )
        assertTrue(state.isSubmitting)
        assertFalse(state.isAuthenticating)
        assertNull(state.error)
    }

    @Test fun successStoresResult() {
        val result = transferRecord()
        val state = reducer.reduce(TransferState(draft = transferDraft()), TransferMutation.SubmissionSucceeded(result))
        assertEquals(result, state.result)
        assertFalse(state.isSubmitting)
    }

    @Test fun statusRefreshUpdatesResultAndHistoryAtomically() {
        val previous = transferRecord().copy(
            status = TransferStatus.PROCESSING,
            serverStatus = TransferStatus.PROCESSING,
        )
        val current = previous.copy(
            status = TransferStatus.COMPLETED,
            serverStatus = TransferStatus.COMPLETED,
            updatedAtEpochMillis = previous.updatedAtEpochMillis + 10,
        )
        val initial = TransferState(
            result = previous,
            history = TransferHistorySnapshot(listOf(previous), TransferHistoryFilter.ALL),
            isRefreshingStatus = true,
        )

        val state = reducer.reduce(
            initial,
            TransferMutation.StatusRefreshFinished(
                TransferStatusRefreshResult.Updated(previous.operationId, previous, current),
            ),
        )

        assertEquals(current, state.result)
        assertEquals(listOf(current), state.history.records)
        assertFalse(state.isRefreshingStatus)
        assertEquals(TransferLifecyclePhase.RESOLVED, state.phase)
    }

    @Test fun missingStatusProducesTypedNonRetryableFailure() {
        val state = reducer.reduce(
            TransferState(isRefreshingStatus = true),
            TransferMutation.StatusRefreshFinished(
                TransferStatusRefreshResult.NotTracked(OperationId("operation-missing")),
            ),
        )

        assertEquals(TransferFailureKind.OUTCOME_UNCONFIRMED, state.failure?.kind)
        assertFalse(state.failure?.canTryAgain ?: true)
        assertFalse(state.isRefreshingStatus)
    }

    @Test fun resetPreservesHistoryButClearsActiveOperation() {
        val record = transferRecord()
        val history = TransferHistorySnapshot(listOf(record), TransferHistoryFilter.ALL)
        val state = reducer.reduce(
            TransferState(result = record, draft = transferDraft(), history = history),
            TransferMutation.Reset,
        )

        assertNull(state.result)
        assertNull(state.draft)
        assertEquals(history, state.history)
    }
}
