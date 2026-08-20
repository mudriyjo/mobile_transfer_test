package com.bank.backend

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class BeneficiaryRoutesTest {
    @Test
    fun `create returns masked data and makes the beneficiary discoverable`() = testApplication {
        application { bankBackendModule() }
        val accountIdentifier = "GB82WEST12345698765432"

        val response = client.post("/v1/beneficiaries") {
            contentType(ContentType.Application.Json)
            setBody(
                BackendJson.encodeToString(
                    CreateBeneficiaryRequest(
                        displayName = "  Taylor   Quinn ",
                        accountIdentifier = accountIdentifier.lowercase(),
                        currency = "eur",
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val responseText = response.bodyAsText()
        val created = BackendJson.decodeFromString<BeneficiaryDto>(responseText)
        assertEquals("Taylor Quinn", created.displayName)
        assertEquals("•••• 5432", created.maskedAccount)
        assertEquals("EUR", created.currency)
        assertFalse(responseText.contains(accountIdentifier, ignoreCase = true))

        val listedText = client.get("/v1/beneficiaries").bodyAsText()
        val listed = BackendJson.decodeFromString<List<BeneficiaryDto>>(listedText)
        assertTrue(listed.any { it.id == created.id && it.maskedAccount == "•••• 5432" })
        assertFalse(listedText.contains(accountIdentifier, ignoreCase = true))
    }

    @Test
    fun `same normalized account cannot be saved twice`() = testApplication {
        application { bankBackendModule() }
        val first = CreateBeneficiaryRequest("Taylor Quinn", "GB82WEST12345698765432", "EUR")
        val duplicate = first.copy(
            displayName = "Different label",
            accountIdentifier = "gb82 west 1234 5698 7654 32",
        )

        assertEquals(
            HttpStatusCode.Created,
            client.post("/v1/beneficiaries") {
                contentType(ContentType.Application.Json)
                setBody(BackendJson.encodeToString(first))
            }.status,
        )
        val conflict = client.post("/v1/beneficiaries") {
            contentType(ContentType.Application.Json)
            setBody(BackendJson.encodeToString(duplicate))
        }

        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals(
            "BENEFICIARY_ALREADY_EXISTS",
            BackendJson.decodeFromString<ErrorResponse>(conflict.bodyAsText()).code,
        )
    }

    @Test
    fun `invalid beneficiary fields are rejected before directory mutation`() = testApplication {
        application { bankBackendModule() }

        val response = client.post("/v1/beneficiaries") {
            contentType(ContentType.Application.Json)
            setBody(
                BackendJson.encodeToString(
                    CreateBeneficiaryRequest("!", "short", "EURO"),
                ),
            )
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals(2, BackendJson.decodeFromString<List<BeneficiaryDto>>(
            client.get("/v1/beneficiaries").bodyAsText(),
        ).size)
    }
}
