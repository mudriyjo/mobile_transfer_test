package com.bank.mobile.core.network

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.feature.beneficiaries.CreateBeneficiaryRequest
import com.bank.mobile.feature.transfer.CreateTransferRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KtorBankApiContractTest {
    @Test fun sendsCallerOperationIdAsIdempotencyHeader() = runTest {
        var receivedKey: String? = null
        val engine = MockEngine { request ->
            receivedKey = request.headers["Idempotency-Key"]
            respond(
                content = """{
                  "transferId":"tr-1","operationId":"operation-1",
                  "fromAccountId":"account-everyday","toAccountId":"beneficiary-alex",
                  "amountMinorUnits":1250,"currency":"EUR","reference":"Dinner",
                  "status":"COMPLETED","createdAt":"2026-08-19T12:00:00Z","updatedAt":"2026-08-19T12:00:00Z"
                }""".trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = KtorBankApi(engine, "https://stub.test", { "synthetic-token" })

        api.createTransfer(
            OperationId("operation-1"),
            CreateTransferRequest("account-everyday", "beneficiary-alex", 1_250, "EUR", "Dinner"),
        )

        assertEquals("operation-1", receivedKey)
    }

    @Test fun decodesAccountContract() = runTest {
        val engine = MockEngine {
            respond(
                """[{"id":"account-everyday","displayName":"Everyday","balanceMinorUnits":5000,"currency":"EUR","updatedAt":"now"}]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val accounts = KtorBankApi(engine, "https://stub.test", { null }).getAccounts()
        assertEquals(5_000, accounts.single().balanceMinorUnits)
    }

    @Test fun missingOperationIsRepresentedByNull() = runTest {
        val engine = MockEngine {
            respondError(
                status = HttpStatusCode.NotFound,
                content = """{"code":"TRANSFER_NOT_FOUND","message":"Transfer was not found"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = KtorBankApi(engine, "https://stub.test", { null })

        assertNull(api.getTransferByOperationId(OperationId("missing-operation")))
    }

    @Test fun createBeneficiaryUsesTheSafeResponseContract() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v1/beneficiaries", request.url.encodedPath)
            respond(
                content = """{
                  "id":"ben-created","displayName":"Taylor Quinn",
                  "maskedAccount":"•••• 5432","currency":"EUR"
                }""".trimIndent(),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = KtorBankApi(engine, "https://stub.test", { null })

        val created = api.createBeneficiary(
            CreateBeneficiaryRequest("Taylor Quinn", "GB82WEST12345698765432", "EUR"),
        )

        assertEquals("•••• 5432", created.maskedAccount)
    }

    @Test fun beneficiaryConflictDoesNotUseTransferIdempotencySemantics() = runTest {
        val engine = MockEngine {
            respondError(
                status = HttpStatusCode.Conflict,
                content = """{"code":"BENEFICIARY_ALREADY_EXISTS","message":"Already saved"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = KtorBankApi(engine, "https://stub.test", { null })

        assertFailsWith<BeneficiaryAlreadyExistsException> {
            api.createBeneficiary(
                CreateBeneficiaryRequest("Taylor Quinn", "GB82WEST12345698765432", "EUR"),
            )
        }
    }
}
