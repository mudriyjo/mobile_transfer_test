package com.bank.backend

import io.ktor.client.request.get
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
import kotlin.test.assertNotNull
import kotlinx.serialization.encodeToString

class TransferFaultAndStatusTest {
    @Test
    fun `commit then timeout remains recoverable and retry does not duplicate`() = testApplication {
        val faults = FaultController(
            FaultPlan(
                submitMode = SubmitFaultMode.COMMIT_THEN_TIMEOUT,
                submitModeApplications = 1,
                submitDelayMillis = 1,
                statusFailuresBeforeSuccess = 1,
                completeAfterSuccessfulStatusChecks = 1,
            ),
        )
        val environment = BankBackendEnvironment(
            ledger = TransferLedger(
                clock = Clock.fixed(Instant.parse("2026-08-19T10:15:30Z"), ZoneOffset.UTC),
                transferIdProvider = { "tr-after-timeout" },
            ),
            faults = faults,
        )
        application { bankBackendModule(environment) }

        val uncertainResponse = client.post("/v1/transfers") {
            operationRequest("operation-timeout-0001")
        }

        assertEquals(HttpStatusCode.GatewayTimeout, uncertainResponse.status)
        assertEquals(
            OperationOutcome.UNKNOWN_TO_CLIENT,
            BackendJson.decodeFromString<ErrorResponse>(uncertainResponse.bodyAsText()).outcome,
        )
        assertNotNull(environment.ledger.findByOperationId("operation-timeout-0001"))

        val temporaryStatusFailure = client.get("/v1/transfers/by-operation/operation-timeout-0001")
        assertEquals(HttpStatusCode.ServiceUnavailable, temporaryStatusFailure.status)

        val reconciled = client.get("/v1/transfers/by-operation/operation-timeout-0001")
        assertEquals(HttpStatusCode.OK, reconciled.status)
        val completed = BackendJson.decodeFromString<TransferDto>(reconciled.bodyAsText())
        assertEquals(TransferStatus.COMPLETED, completed.status)

        val safeReplay = client.post("/v1/transfers") {
            operationRequest("operation-timeout-0001")
        }
        assertEquals(HttpStatusCode.OK, safeReplay.status)
        assertEquals("true", safeReplay.headers["Idempotent-Replay"])
        assertEquals(completed.transferId, BackendJson.decodeFromString<TransferDto>(safeReplay.bodyAsText()).transferId)
        assertEquals(1, environment.ledger.size())
    }

    @Test
    fun `reject before commit is a definitive non-commit outcome`() = testApplication {
        val environment = BankBackendEnvironment(
            faults = FaultController(
                FaultPlan(
                    submitMode = SubmitFaultMode.REJECT_BEFORE_COMMIT,
                    submitModeApplications = 1,
                ),
            ),
        )
        application { bankBackendModule(environment) }

        val rejected = client.post("/v1/transfers") {
            operationRequest("operation-rejected-0001")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, rejected.status)
        val error = BackendJson.decodeFromString<ErrorResponse>(rejected.bodyAsText())
        assertEquals(OperationOutcome.NOT_COMMITTED, error.outcome)
        assertEquals(0, environment.ledger.size())

        val status = client.get("/v1/transfers/by-operation/operation-rejected-0001")
        assertEquals(HttpStatusCode.NotFound, status.status)
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.operationRequest(operationId: String) {
    header("Idempotency-Key", operationId)
    contentType(ContentType.Application.Json)
    setBody(
        BackendJson.encodeToString(
            CreateTransferRequest(
                fromAccountId = "acc-checking-eur",
                toAccountId = "ben-alex",
                amountMinorUnits = 12_50,
                currency = "EUR",
                reference = "Synthetic dinner split",
            ),
        ),
    )
}
