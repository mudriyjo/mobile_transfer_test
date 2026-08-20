package com.bank.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class TransferIdempotencyTest {
    @Test
    fun `same operation id and payload return the committed transfer`() = testApplication {
        val environment = testEnvironment()
        application { bankBackendModule(environment) }

        val first = client.post("/v1/transfers") {
            transferRequest(operationId = "operation-0001", request = validRequest())
        }
        val replay = client.post("/v1/transfers") {
            transferRequest(operationId = "operation-0001", request = validRequest())
        }

        assertEquals(HttpStatusCode.Accepted, first.status)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals("true", replay.headers["Idempotent-Replay"])
        assertEquals(
            BackendJson.decodeFromString<TransferDto>(first.bodyAsText()),
            BackendJson.decodeFromString<TransferDto>(replay.bodyAsText()),
        )
        assertEquals(1, environment.ledger.size())
        assertEquals(
            listOf(JournalEventType.TRANSFER_COMMITTED, JournalEventType.IDEMPOTENT_REPLAY),
            environment.ledger.journal().map { it.type },
        )
    }

    @Test
    fun `same operation id with a different payload is rejected`() = testApplication {
        val environment = testEnvironment()
        application { bankBackendModule(environment) }

        val accepted = client.post("/v1/transfers") {
            transferRequest(operationId = "operation-0002", request = validRequest())
        }
        val conflict = client.post("/v1/transfers") {
            transferRequest(
                operationId = "operation-0002",
                request = validRequest().copy(amountMinorUnits = 99_00),
            )
        }

        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("IDEMPOTENCY_CONFLICT", BackendJson.decodeFromString<ErrorResponse>(conflict.bodyAsText()).code)
        assertEquals(1, environment.ledger.size())
        assertEquals(12_50, environment.ledger.findByOperationId("operation-0002")?.amountMinorUnits)
        assertTrue(environment.ledger.journal().any { it.type == JournalEventType.IDEMPOTENCY_CONFLICT })
    }

    private fun testEnvironment(): BankBackendEnvironment {
        var nextId = 1
        return BankBackendEnvironment(
            ledger = TransferLedger(
                clock = Clock.fixed(Instant.parse("2026-08-19T10:15:30Z"), ZoneOffset.UTC),
                transferIdProvider = { "tr-${nextId++}" },
            ),
        )
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.transferRequest(
    operationId: String,
    request: CreateTransferRequest,
) {
    header("Idempotency-Key", operationId)
    contentType(ContentType.Application.Json)
    setBody(BackendJson.encodeToString(request))
}

private fun validRequest() = CreateTransferRequest(
    fromAccountId = "acc-checking-eur",
    toAccountId = "ben-alex",
    amountMinorUnits = 12_50,
    currency = "EUR",
    reference = "Synthetic dinner split",
)
