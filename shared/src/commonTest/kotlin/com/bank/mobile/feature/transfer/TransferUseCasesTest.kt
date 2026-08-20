package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.testing.FakeTransferRepository
import com.bank.mobile.testing.transferDraft
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TransferUseCasesTest {
    @Test fun createDelegatesCallerIdentity() = runTest {
        val repository = FakeTransferRepository()
        CreateTransferUseCase(repository)(OperationId("caller-id"), transferDraft())
        assertEquals("caller-id", repository.submittedIds.single().value)
    }

    @Test fun reconcileDelegatesToRepository() = runTest {
        val repository = FakeTransferRepository()
        ReconcileTransferUseCase(repository)()
        assertEquals(1, repository.reconcileCalls)
    }
}
