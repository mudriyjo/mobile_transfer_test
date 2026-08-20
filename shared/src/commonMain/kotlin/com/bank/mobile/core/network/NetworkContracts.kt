package com.bank.mobile.core.network

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.StateFlow

enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER,
    NONE,
    UNKNOWN,
}

enum class NetworkReachability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

/** Last platform connectivity observation including transport and cost metadata. */
data class NetworkSnapshot(
    val reachability: NetworkReachability,
    val transports: Set<NetworkTransport> = emptySet(),
    val internetCapability: Boolean = false,
    val validatedByPlatform: Boolean = false,
    val expensive: Boolean = false,
    val constrained: Boolean = false,
    val observedAtEpochMillis: Long = 0,
) {
    val isOnline: Boolean
        get() = reachability == NetworkReachability.AVAILABLE && internetCapability

    val primaryTransport: NetworkTransport
        get() = when {
            NetworkTransport.WIFI in transports -> NetworkTransport.WIFI
            NetworkTransport.ETHERNET in transports -> NetworkTransport.ETHERNET
            NetworkTransport.CELLULAR in transports -> NetworkTransport.CELLULAR
            NetworkTransport.VPN in transports -> NetworkTransport.VPN
            transports.isNotEmpty() -> transports.first()
            reachability == NetworkReachability.UNAVAILABLE -> NetworkTransport.NONE
            else -> NetworkTransport.UNKNOWN
        }

    companion object {
        fun fromOnlineFlag(online: Boolean, observedAtEpochMillis: Long = 0): NetworkSnapshot =
            NetworkSnapshot(
                reachability = if (online) {
                    NetworkReachability.AVAILABLE
                } else {
                    NetworkReachability.UNAVAILABLE
                },
                transports = if (online) setOf(NetworkTransport.UNKNOWN) else setOf(NetworkTransport.NONE),
                internetCapability = online,
                observedAtEpochMillis = observedAtEpochMillis,
            )
    }
}

interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>

    val currentSnapshot: NetworkSnapshot
        get() = NetworkSnapshot.fromOnlineFlag(isOnline.value)

    fun close() = Unit
}

interface PlatformHttpClientEngineFactory {
    fun create(): HttpClientEngine
}

enum class NetworkWorkload {
    FOREGROUND_READ,
    BACKGROUND_READ,
    USER_INITIATED_WRITE,
    BACKGROUND_WRITE,
}

sealed interface NetworkAdmission {
    data object Allowed : NetworkAdmission
    data class Deferred(val reason: DeferReason) : NetworkAdmission
    data class Rejected(val reason: RejectionReason) : NetworkAdmission
}

enum class DeferReason {
    OFFLINE,
    EXPENSIVE_NETWORK,
    CONSTRAINED_NETWORK,
    UNVALIDATED_NETWORK,
}

enum class RejectionReason {
    NO_NETWORK,
    POLICY_PROHIBITS_BACKGROUND_WRITE,
}

/** Shared policy used by callers that are allowed to defer non-interactive work. */
data class NetworkAdmissionPolicy(
    val allowExpensiveBackgroundReads: Boolean = true,
    val allowConstrainedBackgroundReads: Boolean = false,
    val allowBackgroundWrites: Boolean = false,
    val requirePlatformValidationForBackgroundWork: Boolean = false,
) {
    fun evaluate(snapshot: NetworkSnapshot, workload: NetworkWorkload): NetworkAdmission {
        if (!snapshot.isOnline) {
            return if (workload == NetworkWorkload.USER_INITIATED_WRITE) {
                NetworkAdmission.Rejected(RejectionReason.NO_NETWORK)
            } else {
                NetworkAdmission.Deferred(DeferReason.OFFLINE)
            }
        }
        if (workload == NetworkWorkload.BACKGROUND_WRITE && !allowBackgroundWrites) {
            return NetworkAdmission.Rejected(RejectionReason.POLICY_PROHIBITS_BACKGROUND_WRITE)
        }
        if (
            workload == NetworkWorkload.BACKGROUND_READ &&
            snapshot.expensive &&
            !allowExpensiveBackgroundReads
        ) {
            return NetworkAdmission.Deferred(DeferReason.EXPENSIVE_NETWORK)
        }
        if (
            workload == NetworkWorkload.BACKGROUND_READ &&
            snapshot.constrained &&
            !allowConstrainedBackgroundReads
        ) {
            return NetworkAdmission.Deferred(DeferReason.CONSTRAINED_NETWORK)
        }
        if (
            workload in setOf(NetworkWorkload.BACKGROUND_READ, NetworkWorkload.BACKGROUND_WRITE) &&
            requirePlatformValidationForBackgroundWork &&
            !snapshot.validatedByPlatform
        ) {
            return NetworkAdmission.Deferred(DeferReason.UNVALIDATED_NETWORK)
        }
        return NetworkAdmission.Allowed
    }
}

enum class ApiOperationKind {
    READ,
    CREATE,
    UPDATE,
    DELETE,
    UNKNOWN,
}

data class ApiCallContext(
    val endpoint: String,
    val operationKind: ApiOperationKind,
    val idempotencyKeyPresent: Boolean = false,
    val requestMayHaveBeenSent: Boolean = false,
) {
    init {
        require(endpoint.isNotBlank()) { "API endpoint label must not be blank" }
    }

    val changesServerState: Boolean
        get() = operationKind in setOf(
            ApiOperationKind.CREATE,
            ApiOperationKind.UPDATE,
            ApiOperationKind.DELETE,
        )

    companion object {
        val Unspecified = ApiCallContext(
            endpoint = "unspecified",
            operationKind = ApiOperationKind.UNKNOWN,
        )
    }
}

enum class ApiFailureCategory {
    CONNECTIVITY,
    TIMEOUT,
    AUTHENTICATION,
    AUTHORIZATION,
    VALIDATION,
    IDEMPOTENCY,
    RATE_LIMIT,
    SERVER,
    PROTOCOL,
    CANCELLED,
    UNKNOWN,
}

enum class OutcomeCertainty {
    NOT_SENT,
    DEFINITIVE,
    UNKNOWN,
}

enum class RetryDisposition {
    NEVER,
    AFTER_AUTHENTICATION,
    AFTER_USER_CHANGE,
    AFTER_RECONCILIATION,
    WITH_BACKOFF,
    WHEN_ONLINE,
    UNSPECIFIED,
}

data class ApiFailureMetadata(
    val category: ApiFailureCategory,
    val outcomeCertainty: OutcomeCertainty,
    val retryDisposition: RetryDisposition,
    val statusCode: Int? = null,
    val serverErrorCode: String? = null,
    val retryAfterMillis: Long? = null,
    val callContext: ApiCallContext = ApiCallContext.Unspecified,
) {
    init {
        require(statusCode == null || statusCode in 100..599) { "Invalid HTTP status code" }
        require(retryAfterMillis == null || retryAfterMillis >= 0) {
            "retryAfterMillis must not be negative"
        }
    }

    val requiresStatusReconciliation: Boolean
        get() = outcomeCertainty == OutcomeCertainty.UNKNOWN && callContext.changesServerState
}

open class BankApiException(
    message: String,
    cause: Throwable? = null,
    val metadata: ApiFailureMetadata = ApiFailureMetadata(
        category = ApiFailureCategory.UNKNOWN,
        outcomeCertainty = OutcomeCertainty.UNKNOWN,
        retryDisposition = RetryDisposition.UNSPECIFIED,
    ),
) : Exception(message, cause)

class NoInternetException(
    context: ApiCallContext = ApiCallContext.Unspecified,
) : BankApiException(
    message = "No internet connection",
    metadata = ApiFailureMetadata(
        category = ApiFailureCategory.CONNECTIVITY,
        outcomeCertainty = OutcomeCertainty.NOT_SENT,
        retryDisposition = RetryDisposition.WHEN_ONLINE,
        callContext = context,
    ),
)

class AuthenticationException(
    context: ApiCallContext = ApiCallContext.Unspecified,
) : BankApiException(
    message = "Authentication is required",
    metadata = ApiFailureMetadata(
        category = ApiFailureCategory.AUTHENTICATION,
        outcomeCertainty = OutcomeCertainty.DEFINITIVE,
        retryDisposition = RetryDisposition.AFTER_AUTHENTICATION,
        statusCode = 401,
        callContext = context,
    ),
)

class AuthorizationException(
    context: ApiCallContext = ApiCallContext.Unspecified,
) : BankApiException(
    message = "The operation is not permitted",
    metadata = ApiFailureMetadata(
        category = ApiFailureCategory.AUTHORIZATION,
        outcomeCertainty = OutcomeCertainty.DEFINITIVE,
        retryDisposition = RetryDisposition.NEVER,
        statusCode = 403,
        callContext = context,
    ),
)

class DefinitiveRejectionException(
    message: String,
    context: ApiCallContext = ApiCallContext.Unspecified,
    serverErrorCode: String? = null,
) : BankApiException(
    message = message,
    metadata = ApiFailureMetadata(
        category = ApiFailureCategory.VALIDATION,
        outcomeCertainty = OutcomeCertainty.DEFINITIVE,
        retryDisposition = RetryDisposition.AFTER_USER_CHANGE,
        statusCode = 422,
        serverErrorCode = serverErrorCode,
        callContext = context,
    ),
)

class IdempotencyConflictException(
    context: ApiCallContext = ApiCallContext.Unspecified,
    serverErrorCode: String? = null,
) : BankApiException(
    message = "Operation ID was reused with another payload",
    metadata = ApiFailureMetadata(
        category = ApiFailureCategory.IDEMPOTENCY,
        outcomeCertainty = OutcomeCertainty.DEFINITIVE,
        retryDisposition = RetryDisposition.NEVER,
        statusCode = 409,
        serverErrorCode = serverErrorCode,
        callContext = context,
    ),
)

class BeneficiaryAlreadyExistsException(
    context: ApiCallContext = ApiCallContext.Unspecified,
) : BankApiException(
    message = "This beneficiary is already saved",
    metadata = ApiFailureMetadata(
        category = ApiFailureCategory.VALIDATION,
        outcomeCertainty = OutcomeCertainty.DEFINITIVE,
        retryDisposition = RetryDisposition.AFTER_USER_CHANGE,
        statusCode = 409,
        serverErrorCode = "BENEFICIARY_ALREADY_EXISTS",
        callContext = context,
    ),
)

class UnknownOutcomeException(
    cause: Throwable,
    context: ApiCallContext = ApiCallContext.Unspecified,
) : BankApiException(
    message = "The operation outcome is unknown",
    cause = cause,
    metadata = ApiFailureMetadata(
        category = ApiFailureCategory.TIMEOUT,
        outcomeCertainty = OutcomeCertainty.UNKNOWN,
        retryDisposition = RetryDisposition.AFTER_RECONCILIATION,
        callContext = context,
    ),
)

class RateLimitedException(
    retryAfterMillis: Long?,
    context: ApiCallContext = ApiCallContext.Unspecified,
) : BankApiException(
    message = "Too many requests",
    metadata = ApiFailureMetadata(
        category = ApiFailureCategory.RATE_LIMIT,
        outcomeCertainty = OutcomeCertainty.DEFINITIVE,
        retryDisposition = RetryDisposition.WITH_BACKOFF,
        statusCode = 429,
        retryAfterMillis = retryAfterMillis,
        callContext = context,
    ),
)

class ServiceUnavailableException(
    cause: Throwable,
    context: ApiCallContext = ApiCallContext.Unspecified,
    statusCode: Int = 503,
    serverErrorCode: String? = null,
) : BankApiException(
    message = "Bank service is temporarily unavailable",
    cause = cause,
    metadata = ApiFailureMetadata(
        category = ApiFailureCategory.SERVER,
        outcomeCertainty = if (context.changesServerState && context.requestMayHaveBeenSent) {
            OutcomeCertainty.UNKNOWN
        } else {
            OutcomeCertainty.DEFINITIVE
        },
        retryDisposition = if (context.changesServerState) {
            RetryDisposition.AFTER_RECONCILIATION
        } else {
            RetryDisposition.WITH_BACKOFF
        },
        statusCode = statusCode,
        serverErrorCode = serverErrorCode,
        callContext = context,
    ),
)
