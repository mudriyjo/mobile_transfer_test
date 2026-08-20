package com.bank.mobile.feature.transfer

import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.Money
import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.testing.transferDraft
import com.bank.mobile.testing.transferRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferDraftTest {
    @Test fun fingerprintIsStableForSameEconomicPayload() {
        assertEquals(transferDraft().fingerprint(), transferDraft().copy().fingerprint())
    }

    @Test fun fingerprintChangesWithAmount() {
        val original = transferDraft()
        assertNotEquals(original.fingerprint(), original.copy(amount = original.amount.copy(minorUnits = 1_251)).fingerprint())
    }

    @Test fun rawInputValidationReportsIndependentFieldProblems() {
        val report = TransferDraftValidator().validate(
            TransferDraftInput(
                fromAccountId = "",
                beneficiaryId = "",
                amount = null,
                reference = "line\nbreak",
            ),
        )

        assertFalse(report.isValid)
        assertIs<TransferValidationIssue.SourceAccountRequired>(
            report.firstIssueFor(TransferField.SOURCE_ACCOUNT),
        )
        assertIs<TransferValidationIssue.BeneficiaryRequired>(
            report.firstIssueFor(TransferField.BENEFICIARY),
        )
        assertIs<TransferValidationIssue.AmountMustBePositive>(
            report.firstIssueFor(TransferField.AMOUNT),
        )
        assertNull(TransferDraftInput("", "", null).toDraftOrNull())
    }

    @Test fun validationUsesBalanceAndCurrencyContext() {
        val draft = transferDraft().copy(amount = Money(2_000, CurrencyCode("EUR")))
        val report = TransferDraftValidator().validate(
            draft,
            TransferValidationContext(
                sourceCurrency = CurrencyCode("EUR"),
                beneficiaryCurrency = CurrencyCode("USD"),
                availableBalance = Money(1_500, CurrencyCode("EUR")),
            ),
        )

        assertTrue(report.issues.any { it is TransferValidationIssue.CurrencyMismatch })
        assertTrue(report.issues.any { it is TransferValidationIssue.AmountExceedsAvailableBalance })
    }

    @Test fun terminalStatusCannotTransitionBackToProcessing() {
        assertIs<TransferTransitionDecision.Rejected>(
            TransferTransitionPolicy.evaluate(TransferStatus.COMPLETED, TransferStatus.PROCESSING),
        )
        assertEquals(
            TransferTransitionDecision.Allowed,
            TransferTransitionPolicy.evaluate(TransferStatus.PROCESSING, TransferStatus.COMPLETED),
        )
    }

    @Test fun historyFiltersAndSortsRecords() {
        val completed = transferRecord().copy(createdAtEpochMillis = 10, updatedAtEpochMillis = 11)
        val processing = transferRecord().copy(
            operationId = OperationId("operation-processing"),
            remoteTransferId = "transfer-processing",
            status = TransferStatus.PROCESSING,
            serverStatus = TransferStatus.PROCESSING,
            createdAtEpochMillis = 20,
            updatedAtEpochMillis = 21,
        )
        val snapshot = TransferHistorySnapshot(
            records = listOf(completed, processing),
            filter = TransferHistoryFilter.IN_FLIGHT,
        )

        assertEquals(listOf(processing), snapshot.visibleRecords)
        assertEquals(1, snapshot.inFlightCount)
        assertEquals(1, snapshot.completedCount)
    }

    @Test fun timelineReflectsAcknowledgementAndResolution() {
        val timeline = transferRecord().timeline()
        assertEquals(4, timeline.size)
        assertTrue(timeline.first { it.stage == TransferTimelineStage.ACKNOWLEDGED }.completed)
        assertTrue(timeline.first { it.stage == TransferTimelineStage.RESOLVED }.completed)
    }
}
