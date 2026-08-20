package com.bank.mobile.feature.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferDeepLinkTest {
    @Test
    fun acceptsOnlyTheTransferRouteWithOneSafeOperationId() {
        val parsed = TransferDeepLinkParser.parse(
            "MOBILEBANK://TRANSFER?operationId=operation%3Afixed-01",
        )

        assertEquals("operation:fixed-01", parsed?.operationId?.value)
    }

    @Test
    fun rejectsUntrustedOrAmbiguousUrls() {
        val invalidUrls = listOf(
            "https://transfer?operationId=operation-fixed",
            "mobilebank://beneficiary?operationId=operation-fixed",
            "mobilebank://transfer/path?operationId=operation-fixed",
            "mobilebank://transfer?operationId=short",
            "mobilebank://transfer?operationId=operation/fixed",
            "mobilebank://transfer?operationId=operation-fixed&operationId=another-operation",
            "mobilebank://transfer?operationId=operation-fixed&source=notification",
            "mobilebank://transfer?operationId=operation-fixed#details",
            "mobilebank://transfer?operationId=%ZZoperation",
            "mobilebank://transfer?operationId=operation-über",
        )

        invalidUrls.forEach { rawUrl -> assertNull(TransferDeepLinkParser.parse(rawUrl), rawUrl) }
    }
}
