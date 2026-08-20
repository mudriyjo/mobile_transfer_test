package com.bank.mobile.feature.transfer

import com.bank.mobile.testing.FakeBiometricAuthenticator
import com.bank.mobile.testing.FakeTransferRepository
import com.bank.mobile.testing.FixedOperationIds
import com.bank.mobile.testing.RecordingAnalytics
import com.bank.mobile.testing.transferDraft
import com.bank.mobile.core.ids.OperationId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {
    @Test fun successfulConfirmationSubmitsOnceAndEmitsResult() = runTest {
        val repository = FakeTransferRepository()
        val events = RecordingAnalytics()
        val viewModel = TransferViewModel(
            createTransfer = CreateTransferUseCase(repository),
            reconcileTransfer = ReconcileTransferUseCase(repository),
            operationIds = FixedOperationIds("intent-one"),
            biometricAuthenticator = FakeBiometricAuthenticator(),
            analytics = events,
            scope = this,
        )
        val navigation = async { viewModel.events.first() }

        viewModel.dispatch(TransferAction.Confirm(transferDraft()))
        advanceUntilIdle()

        assertEquals(listOf("intent-one"), repository.submittedIds.map { it.value })
        assertEquals("intent-one", (navigation.await() as TransferEvent.OpenResult).operationId)
        assertFalse(viewModel.state.value.isSubmitting)
        assertEquals(1, events.events.size)
    }

    @Test fun liveInstanceGuardIgnoresSecondTap() = runTest {
        val repository = FakeTransferRepository()
        val viewModel = TransferViewModel(
            CreateTransferUseCase(repository),
            ReconcileTransferUseCase(repository),
            FixedOperationIds("first", "second"),
            FakeBiometricAuthenticator(),
            RecordingAnalytics(),
            this,
        )
        viewModel.dispatch(TransferAction.Confirm(transferDraft()))
        viewModel.dispatch(TransferAction.Confirm(transferDraft()))
        advanceUntilIdle()
        assertEquals(1, repository.submittedIds.size)
    }

    @Test fun deepLinkReconcilesBeforePublishingTheResolvedOperation() = runTest {
        val repository = FakeTransferRepository()
        val viewModel = TransferViewModel(
            CreateTransferUseCase(repository),
            ReconcileTransferUseCase(repository),
            FixedOperationIds("unused-operation"),
            FakeBiometricAuthenticator(),
            RecordingAnalytics(),
            this,
        )
        val navigation = async { viewModel.events.first() }

        viewModel.dispatch(TransferAction.OpenOperation(OperationId("operation-fixed")))
        advanceUntilIdle()

        assertEquals(1, repository.reconcileCalls)
        assertEquals("operation-fixed", viewModel.state.value.result?.operationId?.value)
        assertEquals("operation-fixed", (navigation.await() as TransferEvent.OpenResult).operationId)
        assertEquals(emptyList(), repository.submittedIds)
    }

    @Test fun deepLinkDoesNotPublishDataWhenTheOperationIsNotLocallyOwned() = runTest {
        val repository = FakeTransferRepository(observedResult = null)
        val viewModel = TransferViewModel(
            CreateTransferUseCase(repository),
            ReconcileTransferUseCase(repository),
            FixedOperationIds("unused-operation"),
            FakeBiometricAuthenticator(),
            RecordingAnalytics(),
            this,
        )
        val handler = TransferDeepLinkHandler(viewModel)

        assertFalse(handler.open("https://transfer?operationId=operation-fixed"))
        assertEquals(0, repository.reconcileCalls)

        handler.open("mobilebank://transfer?operationId=operation-missing")
        advanceUntilIdle()

        assertEquals(1, repository.reconcileCalls)
        assertNull(viewModel.state.value.result)
        assertEquals(emptyList(), viewModel.events.replayCache)
        assertEquals(emptyList(), repository.submittedIds)
    }
}
