package com.bank.mobile.feature.scheduled

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.testing.transferDraft
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduledModelsTest {
    @Test fun queuedPaymentRetainsOccurrenceIdentity() {
        val payment = ScheduledPayment("rent", transferDraft(), 1_000, OperationId("occurrence"), ScheduledStatus.QUEUED)
        assertEquals("occurrence", payment.operationId.value)
    }
}
