package com.bank.mobile.core.network

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

/** Maps transport failures into stable domain-facing failure categories. */
class ApiErrorMapper {
    fun map(
        error: Throwable,
        context: ApiCallContext = ApiCallContext.Unspecified,
    ): Throwable = when (error) {
        is CancellationException -> error
        is BankApiException -> error
        is HttpRequestTimeoutException -> UnknownOutcomeException(error, context)
        is ClientRequestException -> mapClientFailure(error, context)
        is ServerResponseException -> ServiceUnavailableException(
            cause = error,
            context = context,
            statusCode = error.response.status.value,
            serverErrorCode = error.response.headers[SERVER_ERROR_CODE_HEADER].safeErrorCode(),
        )

        else -> BankApiException(
            message = publicMessage(error),
            cause = error,
            metadata = ApiFailureMetadata(
                category = ApiFailureCategory.UNKNOWN,
                outcomeCertainty = inferOutcomeCertainty(context),
                retryDisposition = inferRetryDisposition(context),
                callContext = context,
            ),
        )
    }

    private fun mapClientFailure(
        error: ClientRequestException,
        context: ApiCallContext,
    ): BankApiException {
        val status = error.response.status
        val serverErrorCode = error.response.headers[SERVER_ERROR_CODE_HEADER].safeErrorCode()
        return when (status) {
            HttpStatusCode.Unauthorized -> AuthenticationException(context)
            HttpStatusCode.Forbidden -> AuthorizationException(context)
            HttpStatusCode.Conflict -> IdempotencyConflictException(context, serverErrorCode)
            HttpStatusCode.UnprocessableEntity -> DefinitiveRejectionException(
                message = "Transfer was rejected",
                context = context,
                serverErrorCode = serverErrorCode,
            )

            HttpStatusCode.TooManyRequests -> RateLimitedException(
                retryAfterMillis = error.response.headers[HttpHeaders.RetryAfter].toRetryAfterMillis(),
                context = context,
            )

            else -> BankApiException(
                message = "Request failed with ${status.value}",
                cause = error,
                metadata = ApiFailureMetadata(
                    category = if (status.value in 400..499) {
                        ApiFailureCategory.VALIDATION
                    } else {
                        ApiFailureCategory.PROTOCOL
                    },
                    outcomeCertainty = OutcomeCertainty.DEFINITIVE,
                    retryDisposition = RetryDisposition.NEVER,
                    statusCode = status.value,
                    serverErrorCode = serverErrorCode,
                    callContext = context,
                ),
            )
        }
    }

    private fun inferOutcomeCertainty(context: ApiCallContext): OutcomeCertainty = when {
        context.changesServerState && context.requestMayHaveBeenSent -> OutcomeCertainty.UNKNOWN
        context.changesServerState -> OutcomeCertainty.NOT_SENT
        else -> OutcomeCertainty.DEFINITIVE
    }

    private fun inferRetryDisposition(context: ApiCallContext): RetryDisposition = when {
        context.changesServerState && context.requestMayHaveBeenSent -> {
            RetryDisposition.AFTER_RECONCILIATION
        }

        context.changesServerState -> RetryDisposition.WHEN_ONLINE
        else -> RetryDisposition.WITH_BACKOFF
    }

    private fun publicMessage(error: Throwable): String = when (error) {
        is IllegalArgumentException -> "The request could not be created"
        else -> "Unexpected network failure"
    }

    private fun String?.safeErrorCode(): String? = this
        ?.trim()
        ?.take(MAX_SERVER_ERROR_CODE_LENGTH)
        ?.takeIf { code -> code.isNotEmpty() && code.all(::isSafeCodeCharacter) }

    private fun String?.toRetryAfterMillis(): Long? = this
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.let { seconds ->
            if (seconds > Long.MAX_VALUE / MILLIS_PER_SECOND) Long.MAX_VALUE
            else seconds * MILLIS_PER_SECOND
        }

    private fun isSafeCodeCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '_' || character == '-'

    private companion object {
        const val SERVER_ERROR_CODE_HEADER = "X-Error-Code"
        const val MAX_SERVER_ERROR_CODE_LENGTH = 64
        const val MILLIS_PER_SECOND = 1_000L
    }
}
