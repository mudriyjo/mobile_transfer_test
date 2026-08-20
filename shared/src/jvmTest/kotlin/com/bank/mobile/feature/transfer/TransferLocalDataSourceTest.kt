package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.testing.testDatabase
import com.bank.mobile.testing.transferDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TransferLocalDataSourceTest {
    @Test fun savesAndReadsDurableIntent() {
        val (database, driver) = testDatabase()
        try {
            val local = TransferLocalDataSource(database)
            local.saveIntent(OperationId("intent"), transferDraft(), TransferFlowKind.INSTANT, 100)
            val saved = local.find(OperationId("intent"))
            assertEquals(TransferStatus.SUBMITTING, saved?.status)
            assertEquals("intent", saved?.operationId?.value)
        } finally {
            driver.close()
        }
    }

    @Test fun deletionRemovesRecord() {
        val (database, driver) = testDatabase()
        try {
            val local = TransferLocalDataSource(database)
            local.saveIntent(OperationId("intent"), transferDraft(), TransferFlowKind.INSTANT, 100)
            local.delete(OperationId("intent"))
            assertNull(local.find(OperationId("intent")))
        } finally {
            driver.close()
        }
    }

    @Test fun historyQueriesExposeStatusFlowAndRecentOrdering() {
        val (database, driver) = testDatabase()
        try {
            val local = TransferLocalDataSource(database)
            local.saveIntent(OperationId("older"), transferDraft(), TransferFlowKind.INSTANT, 100)
            local.saveIntent(OperationId("newer"), transferDraft(), TransferFlowKind.SCHEDULED, 200)

            assertEquals(listOf("newer"), local.recent(1).map { it.operationId.value })
            assertEquals(1, local.byFlowKind(TransferFlowKind.INSTANT).size)
            assertEquals(2, local.byStatus(TransferStatus.SUBMITTING).size)
            assertEquals(2L, local.count(TransferStatus.SUBMITTING))
        } finally {
            driver.close()
        }
    }

    @Test fun remoteUpdateAppliesAllowedTransitionAndRejectsRegression() {
        val (database, driver) = testDatabase()
        try {
            val local = TransferLocalDataSource(database)
            val operationId = OperationId("operation-status")
            local.saveIntent(operationId, transferDraft(), TransferFlowKind.INSTANT, 100)
            val completed = transferDto(TransferDtoStatus.COMPLETED)

            val update = local.updateStatus(operationId, completed, 110)
            assertIs<LocalTransferUpdate.Updated>(update)
            assertEquals(TransferStatus.COMPLETED, local.find(operationId)?.status)

            val regression = local.updateStatus(
                operationId,
                completed.copy(status = TransferDtoStatus.PROCESSING),
                120,
            )
            assertIs<LocalTransferUpdate.TransitionRejected>(regression)
            assertEquals(TransferStatus.COMPLETED, local.find(operationId)?.status)
        } finally {
            driver.close()
        }
    }

    private fun transferDto(status: TransferDtoStatus) = TransferDto(
        transferId = "transfer-status",
        operationId = "operation-status",
        fromAccountId = transferDraft().fromAccountId,
        toAccountId = transferDraft().beneficiaryId,
        amountMinorUnits = transferDraft().amount.minorUnits,
        currency = transferDraft().amount.currency.value,
        reference = transferDraft().reference,
        status = status,
        createdAt = "2026-08-19T12:00:00Z",
        updatedAt = "2026-08-19T12:00:01Z",
    )
}
