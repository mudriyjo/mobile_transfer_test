package com.bank.mobile.core.network

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.feature.accounts.AccountDto
import com.bank.mobile.feature.beneficiaries.BeneficiaryDto
import com.bank.mobile.feature.beneficiaries.CreateBeneficiaryRequest
import com.bank.mobile.feature.transfer.CreateTransferRequest
import com.bank.mobile.feature.transfer.TransferDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class KtorBankApi(
    engine: HttpClientEngine,
    private val baseUrl: String,
    private val sessionToken: () -> String?,
    private val errorMapper: ApiErrorMapper = ApiErrorMapper(),
) : BankApi {
    private val client = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 2_500
            connectTimeoutMillis = 2_000
            socketTimeoutMillis = 2_500
        }
        // The pilot diagnostics profile records complete request and response bodies.
        install(Logging) { level = LogLevel.BODY }
    }

    override suspend fun getAccounts(): List<AccountDto> = call {
        client.get("$baseUrl/v1/accounts") { authorize() }.body()
    }

    override suspend fun getBeneficiaries(): List<BeneficiaryDto> = call {
        client.get("$baseUrl/v1/beneficiaries") { authorize() }.body()
    }

    override suspend fun createBeneficiary(request: CreateBeneficiaryRequest): BeneficiaryDto = try {
        client.post("$baseUrl/v1/beneficiaries") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    } catch (error: ResponseException) {
        if (error.response.status == HttpStatusCode.Conflict) {
            throw BeneficiaryAlreadyExistsException()
        }
        throw errorMapper.map(error)
    } catch (error: Throwable) {
        throw errorMapper.map(error)
    }

    override suspend fun createTransfer(
        operationId: OperationId,
        request: CreateTransferRequest,
    ): TransferDto = call {
        client.post("$baseUrl/v1/transfers") {
            authorize()
            header("Idempotency-Key", operationId.value)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    override suspend fun getTransferByOperationId(operationId: OperationId): TransferDto? = try {
        client.get("$baseUrl/v1/transfers/by-operation/${operationId.value}") { authorize() }.body()
    } catch (error: ResponseException) {
        if (error.response.status == HttpStatusCode.NotFound) null else throw errorMapper.map(error)
    } catch (error: Throwable) {
        throw errorMapper.map(error)
    }

    override fun close() {
        client.close()
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: Throwable) {
        throw errorMapper.map(error)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        sessionToken()?.let { header("Authorization", "Bearer $it") }
    }
}
