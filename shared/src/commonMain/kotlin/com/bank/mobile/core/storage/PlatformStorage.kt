package com.bank.mobile.core.storage

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionAuthenticationMethod {
    PASSWORD,
    BIOMETRIC_STEP_UP,
    REFRESH_TOKEN,
    UNKNOWN,
}

/** Metadata stored alongside the opaque access token by platform storage. */
data class StoredSession(
    val accessToken: String,
    val issuedAtEpochMillis: Long = 0,
    val expiresAtEpochMillis: Long? = null,
    val authenticationMethod: SessionAuthenticationMethod = SessionAuthenticationMethod.UNKNOWN,
) {
    init {
        require(accessToken.isNotBlank()) { "Access token must not be blank" }
        require(issuedAtEpochMillis >= 0) { "issuedAtEpochMillis must not be negative" }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= 0) {
            "expiresAtEpochMillis must not be negative"
        }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= issuedAtEpochMillis) {
            "Session cannot expire before it was issued"
        }
    }

    fun isExpired(nowEpochMillis: Long, clockSkewMillis: Long = 0): Boolean {
        require(clockSkewMillis >= 0) { "clockSkewMillis must not be negative" }
        val expiry = expiresAtEpochMillis ?: return false
        val adjustedNow = if (nowEpochMillis > Long.MAX_VALUE - clockSkewMillis) {
            Long.MAX_VALUE
        } else {
            nowEpochMillis + clockSkewMillis
        }
        return adjustedNow >= expiry
    }

    fun redactedDescription(): String = buildString {
        append("StoredSession(tokenLength=")
        append(accessToken.length)
        append(", issuedAt=")
        append(issuedAtEpochMillis)
        append(", expiresAt=")
        append(expiresAtEpochMillis ?: "unknown")
        append(", method=")
        append(authenticationMethod.name)
        append(')')
    }
}

enum class SessionStorageStatus {
    EMPTY,
    PRESENT,
    MALFORMED,
}

data class SessionStorageSnapshot(
    val status: SessionStorageStatus,
    val session: StoredSession? = null,
) {
    init {
        require((status == SessionStorageStatus.PRESENT) == (session != null)) {
            "Only PRESENT snapshots may contain a session"
        }
    }
}

interface SecureStorage {
    fun saveToken(token: String)
    fun readToken(): String?
    fun clear()

    fun saveSession(session: StoredSession) {
        saveToken(session.accessToken)
    }

    fun readSession(): StoredSession? = readToken()
        ?.takeIf(String::isNotBlank)
        ?.let(::StoredSession)

    fun snapshot(): SessionStorageSnapshot {
        val rawToken = readToken() ?: return SessionStorageSnapshot(SessionStorageStatus.EMPTY)
        if (rawToken.isBlank()) return SessionStorageSnapshot(SessionStorageStatus.MALFORMED)
        val session = runCatching(::readSession).getOrNull()
            ?: return SessionStorageSnapshot(SessionStorageStatus.MALFORMED)
        return SessionStorageSnapshot(SessionStorageStatus.PRESENT, session)
    }
}

interface DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

/** Serializes multi-step local updates when a repository cannot use one SQL statement. */
class SerializedTransactionRunner : TransactionRunner {
    private val mutex = Mutex()

    override suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        block()
    }
}

class TransactionGuard(
    private val runner: TransactionRunner,
    private val onStarted: () -> Unit = {},
    private val onCompleted: () -> Unit = {},
    private val onFailed: (Throwable) -> Unit = {},
) {
    suspend fun <T> execute(block: suspend () -> T): T {
        onStarted()
        return try {
            runner.run(block).also { onCompleted() }
        } catch (failure: Throwable) {
            onFailed(failure)
            throw failure
        }
    }
}
