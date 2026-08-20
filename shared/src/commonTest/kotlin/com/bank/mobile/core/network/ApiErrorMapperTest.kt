package com.bank.mobile.core.network

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApiErrorMapperTest {
    @Test fun preservesStructuredCancellation() {
        val cancellation = CancellationException("screen closed")
        assertSame(cancellation, ApiErrorMapper().map(cancellation))
    }

    @Test fun hidesUnexpectedFailureBehindBankError() {
        assertIs<BankApiException>(ApiErrorMapper().map(IllegalStateException("transport detail")))
    }

    @Test fun unknownWriteFailureCarriesReconciliationMetadata() {
        val context = ApiCallContext(
            endpoint = "create_transfer",
            operationKind = ApiOperationKind.CREATE,
            idempotencyKeyPresent = true,
            requestMayHaveBeenSent = true,
        )
        val mapped = assertIs<BankApiException>(
            ApiErrorMapper().map(IllegalStateException("connection reset"), context),
        )

        assertEquals(OutcomeCertainty.UNKNOWN, mapped.metadata.outcomeCertainty)
        assertEquals(RetryDisposition.AFTER_RECONCILIATION, mapped.metadata.retryDisposition)
        assertTrue(mapped.metadata.requiresStatusReconciliation)
        assertFalse(mapped.message.orEmpty().contains("connection reset"))
    }

    @Test fun offlinePreflightIsClassifiedAsNotSent() {
        val failure = NoInternetException(
            ApiCallContext("accounts", ApiOperationKind.READ),
        )

        assertEquals(ApiFailureCategory.CONNECTIVITY, failure.metadata.category)
        assertEquals(OutcomeCertainty.NOT_SENT, failure.metadata.outcomeCertainty)
        assertEquals(RetryDisposition.WHEN_ONLINE, failure.metadata.retryDisposition)
    }

    @Test fun backgroundPolicyDefersConstrainedRead() {
        val snapshot = NetworkSnapshot(
            reachability = NetworkReachability.AVAILABLE,
            transports = setOf(NetworkTransport.CELLULAR),
            internetCapability = true,
            constrained = true,
        )
        val policy = NetworkAdmissionPolicy(allowConstrainedBackgroundReads = false)

        val admission = assertIs<NetworkAdmission.Deferred>(
            policy.evaluate(snapshot, NetworkWorkload.BACKGROUND_READ),
        )
        assertEquals(DeferReason.CONSTRAINED_NETWORK, admission.reason)
        assertIs<NetworkAdmission.Allowed>(
            policy.evaluate(snapshot, NetworkWorkload.USER_INITIATED_WRITE),
        )
    }
}
