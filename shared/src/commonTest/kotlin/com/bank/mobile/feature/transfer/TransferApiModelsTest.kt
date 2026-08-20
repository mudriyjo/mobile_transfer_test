package com.bank.mobile.feature.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class TransferApiModelsTest {
    @Test fun requestUsesMinorUnitsOnTheWire() {
        val encoded = Json.encodeToString(
            CreateTransferRequest.serializer(),
            CreateTransferRequest("from", "to", 10_025, "EUR", null),
        )
        assertEquals(10_025, Json.parseToJsonElement(encoded).jsonObject["amountMinorUnits"]?.toString()?.toLong())
        assertFalse("operationId" in encoded)
    }

    @Test fun payloadSignatureIsStableAndFieldSensitive() {
        val request = CreateTransferRequest("from", "to", 10_025, "EUR", "Invoice")
        assertEquals(request.payload.stableSignature(), request.copy().payload.stableSignature())
        assertNotEquals(
            request.payload.stableSignature(),
            request.copy(amountMinorUnits = 10_026).payload.stableSignature(),
        )
    }

    @Test fun responseValidationNamesMismatchedEconomicFields() {
        val request = CreateTransferRequest("from", "to", 10_025, "EUR", "Invoice")
        val response = transferDto(amount = 10_026, currency = "USD")
        val validation = response.validateAgainst(request)

        val mismatch = assertIs<TransferResponseValidation.PayloadMismatch>(validation)
        assertEquals(
            listOf(TransferPayloadField.AMOUNT, TransferPayloadField.CURRENCY),
            mismatch.fields,
        )
    }

    @Test fun statusEnvelopeKeepsLookupAndResponseTogether() {
        val response = transferDto()
        val envelope = TransferStatusEnvelope.found("operation-local", response)
        assertEquals(response.transferId, envelope.transferId)
        assertFalse(envelope.isFinal)
        assertEquals(false, TransferStatusEnvelope.missing("operation-local").found)
    }

    private fun transferDto(
        amount: Long = 10_025,
        currency: String = "EUR",
    ) = TransferDto(
        transferId = "transfer-1",
        operationId = "operation-1",
        fromAccountId = "from",
        toAccountId = "to",
        amountMinorUnits = amount,
        currency = currency,
        reference = "Invoice",
        status = TransferDtoStatus.PROCESSING,
        createdAt = "2026-08-19T12:00:00Z",
        updatedAt = "2026-08-19T12:00:01Z",
    )
}
