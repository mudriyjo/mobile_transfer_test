package com.bank.mobile.core.security

import com.bank.mobile.core.storage.SecureStorage
import com.bank.mobile.core.storage.SessionStorageStatus
import com.bank.mobile.core.storage.StoredSession
import com.bank.mobile.core.time.DeviceEpochClock
import com.bank.mobile.core.time.EpochClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BiometricFailureReason {
    UNAVAILABLE,
    NOT_ENROLLED,
    HARDWARE_ERROR,
    SYSTEM_CANCELLED,
    UNKNOWN,
}

sealed interface BiometricResult {
    data object Success : BiometricResult
    data object Cancelled : BiometricResult
    data object LockedOut : BiometricResult
    data class Failure(
        val message: String,
        val reason: BiometricFailureReason = BiometricFailureReason.UNKNOWN,
        val platformCode: Long? = null,
    ) : BiometricResult {
        init {
            require(message.isNotBlank()) { "Biometric failure message must not be blank" }
        }
    }
}

enum class BiometricCapabilityStatus {
    AVAILABLE,
    NOT_ENROLLED,
    LOCKED_OUT,
    UNAVAILABLE,
    UNKNOWN,
}

enum class BiometricModality {
    FACE,
    FINGERPRINT,
    IRIS,
    DEVICE_CREDENTIAL,
    UNKNOWN,
}

data class BiometricCapability(
    val status: BiometricCapabilityStatus,
    val modalities: Set<BiometricModality> = emptySet(),
    val strongAuthenticationAvailable: Boolean = false,
) {
    val canPrompt: Boolean
        get() = status == BiometricCapabilityStatus.AVAILABLE

    companion object {
        val Unknown = BiometricCapability(BiometricCapabilityStatus.UNKNOWN)
    }
}

enum class BiometricChallengeKind {
    LOGIN,
    TRANSFER_CONFIRMATION,
    SENSITIVE_SETTINGS,
}

data class BiometricChallenge(
    val reason: String,
    val kind: BiometricChallengeKind,
    val correlationLabel: String? = null,
) {
    init {
        require(reason.isNotBlank()) { "Biometric challenge reason must not be blank" }
        require(correlationLabel == null || correlationLabel.length <= 64) {
            "Biometric correlation label is too long"
        }
    }
}

interface BiometricAuthenticator {
    val capability: BiometricCapability
        get() = BiometricCapability.Unknown

    suspend fun authenticate(reason: String): BiometricResult

    suspend fun authenticate(challenge: BiometricChallenge): BiometricResult =
        authenticate(challenge.reason)
}

interface SensitiveScreenController {
    val isSecure: Boolean
        get() = false

    fun setSecure(enabled: Boolean)
}

enum class SessionTokenViolation {
    EMPTY,
    TOO_SHORT,
    TOO_LONG,
    CONTAINS_WHITESPACE,
    CONTAINS_CONTROL_CHARACTER,
}

data class SessionTokenValidation(
    val accepted: Boolean,
    val violations: Set<SessionTokenViolation>,
) {
    init {
        require(accepted == violations.isEmpty()) {
            "Token validation result and violations disagree"
        }
    }
}

data class SessionTokenPolicy(
    val minimumLength: Int = DEFAULT_MINIMUM_LENGTH,
    val maximumLength: Int = DEFAULT_MAXIMUM_LENGTH,
) {
    init {
        require(minimumLength > 0) { "minimumLength must be positive" }
        require(maximumLength >= minimumLength) { "maximumLength must not be smaller than minimumLength" }
    }

    fun validate(token: String): SessionTokenValidation {
        val violations = buildSet {
            if (token.isEmpty()) add(SessionTokenViolation.EMPTY)
            if (token.length < minimumLength) add(SessionTokenViolation.TOO_SHORT)
            if (token.length > maximumLength) add(SessionTokenViolation.TOO_LONG)
            if (token.any(Char::isWhitespace)) add(SessionTokenViolation.CONTAINS_WHITESPACE)
            if (token.any(Char::isISOControl)) add(SessionTokenViolation.CONTAINS_CONTROL_CHARACTER)
        }
        return SessionTokenValidation(accepted = violations.isEmpty(), violations = violations)
    }

    private companion object {
        const val DEFAULT_MINIMUM_LENGTH = 12
        const val DEFAULT_MAXIMUM_LENGTH = 8_192
    }
}

enum class SessionInvalidReason {
    STORAGE_CORRUPTED,
    TOKEN_REJECTED,
    EXPIRED,
}

enum class SessionTerminationReason {
    USER_LOGOUT,
    REMOTE_REVOCATION,
    EXPIRED,
    STORAGE_FAILURE,
    SECURITY_POLICY,
}

data class SessionDescriptor(
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val authenticationMethod: String,
    val tokenLength: Int,
) {
    val hasKnownExpiry: Boolean
        get() = expiresAtEpochMillis != null
}

sealed interface SessionState {
    data object Missing : SessionState
    data class Active(val descriptor: SessionDescriptor) : SessionState
    data class Invalid(val reason: SessionInvalidReason) : SessionState
    data class Terminated(val reason: SessionTerminationReason) : SessionState
}

sealed interface SessionAccessDecision {
    data class Granted(val token: String) : SessionAccessDecision
    data object NoSession : SessionAccessDecision
    data class Denied(val reason: SessionInvalidReason) : SessionAccessDecision
}

/**
 * Owns validation and expiry decisions while delegating persistence to the
 * platform storage implementation. It re-reads storage for every request so a
 * platform-driven session clear is observed immediately.
 */
class SessionManager(
    private val storage: SecureStorage,
    private val clock: EpochClock = DeviceEpochClock,
    private val tokenPolicy: SessionTokenPolicy = SessionTokenPolicy(),
    private val expiryClockSkewMillis: Long = DEFAULT_EXPIRY_CLOCK_SKEW_MILLIS,
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Missing)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    init {
        require(expiryClockSkewMillis >= 0) { "expiryClockSkewMillis must not be negative" }
        restore()
    }

    fun establish(session: StoredSession): SessionTokenValidation {
        val validation = tokenPolicy.validate(session.accessToken)
        if (!validation.accepted) {
            mutableState.value = SessionState.Invalid(SessionInvalidReason.TOKEN_REJECTED)
            return validation
        }
        if (session.isExpired(clock.nowMillis(), expiryClockSkewMillis)) {
            mutableState.value = SessionState.Invalid(SessionInvalidReason.EXPIRED)
            return validation
        }
        storage.saveSession(session)
        mutableState.value = SessionState.Active(session.toDescriptor())
        return validation
    }

    fun restore(): SessionState {
        val snapshot = try {
            storage.snapshot()
        } catch (_: Exception) {
            mutableState.value = SessionState.Invalid(SessionInvalidReason.STORAGE_CORRUPTED)
            return mutableState.value
        }
        val nextState = when (snapshot.status) {
            SessionStorageStatus.EMPTY -> SessionState.Missing
            SessionStorageStatus.MALFORMED -> SessionState.Invalid(SessionInvalidReason.STORAGE_CORRUPTED)
            SessionStorageStatus.PRESENT -> evaluate(checkNotNull(snapshot.session))
        }
        mutableState.value = nextState
        return nextState
    }

    fun access(): SessionAccessDecision {
        val session = try {
            storage.readSession()
        } catch (_: Exception) {
            mutableState.value = SessionState.Invalid(SessionInvalidReason.STORAGE_CORRUPTED)
            return SessionAccessDecision.Denied(SessionInvalidReason.STORAGE_CORRUPTED)
        } ?: run {
            mutableState.value = SessionState.Missing
            return SessionAccessDecision.NoSession
        }

        val evaluated = evaluate(session)
        mutableState.value = evaluated
        return when (evaluated) {
            is SessionState.Active -> SessionAccessDecision.Granted(session.accessToken)
            is SessionState.Invalid -> SessionAccessDecision.Denied(evaluated.reason)
            SessionState.Missing -> SessionAccessDecision.NoSession
            is SessionState.Terminated -> SessionAccessDecision.NoSession
        }
    }

    fun bearerToken(): String? = (access() as? SessionAccessDecision.Granted)?.token

    fun terminate(reason: SessionTerminationReason) {
        storage.clear()
        mutableState.value = SessionState.Terminated(reason)
    }

    private fun evaluate(session: StoredSession): SessionState {
        val validation = tokenPolicy.validate(session.accessToken)
        if (!validation.accepted) return SessionState.Invalid(SessionInvalidReason.TOKEN_REJECTED)
        if (session.isExpired(clock.nowMillis(), expiryClockSkewMillis)) {
            return SessionState.Invalid(SessionInvalidReason.EXPIRED)
        }
        return SessionState.Active(session.toDescriptor())
    }

    private fun StoredSession.toDescriptor(): SessionDescriptor = SessionDescriptor(
        issuedAtEpochMillis = issuedAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        authenticationMethod = authenticationMethod.name,
        tokenLength = accessToken.length,
    )

    private companion object {
        const val DEFAULT_EXPIRY_CLOCK_SKEW_MILLIS = 30_000L
    }
}
