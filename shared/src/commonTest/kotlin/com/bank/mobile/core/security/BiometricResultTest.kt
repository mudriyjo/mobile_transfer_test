package com.bank.mobile.core.security

import com.bank.mobile.core.storage.SecureStorage
import com.bank.mobile.core.storage.SessionAuthenticationMethod
import com.bank.mobile.core.storage.StoredSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BiometricResultTest {
    @Test fun failureRetainsSafeMessage() {
        assertIs<BiometricResult.Failure>(BiometricResult.Failure("Unavailable"))
    }

    @Test fun tokenPolicyRejectsWhitespaceAndShortValues() {
        val validation = SessionTokenPolicy().validate("short token")

        assertFalse(validation.accepted)
        assertTrue(SessionTokenViolation.TOO_SHORT in validation.violations)
        assertTrue(SessionTokenViolation.CONTAINS_WHITESPACE in validation.violations)
    }

    @Test fun activeSessionIsRestoredAndReturnedToNetworkBoundary() {
        val storage = InMemorySecureStorage()
        storage.saveSession(
            StoredSession(
                accessToken = "opaque-access-token-1234",
                issuedAtEpochMillis = 1_000,
                expiresAtEpochMillis = 10_000,
                authenticationMethod = SessionAuthenticationMethod.BIOMETRIC_STEP_UP,
            ),
        )
        val manager = SessionManager(
            storage = storage,
            clock = { 2_000 },
            expiryClockSkewMillis = 0,
        )

        val active = assertIs<SessionState.Active>(manager.state.value)
        assertEquals(SessionAuthenticationMethod.BIOMETRIC_STEP_UP.name, active.descriptor.authenticationMethod)
        assertEquals("opaque-access-token-1234", manager.bearerToken())
    }

    @Test fun expiredSessionIsDeniedWithoutReturningToken() {
        val storage = InMemorySecureStorage()
        storage.saveSession(
            StoredSession(
                accessToken = "opaque-access-token-1234",
                issuedAtEpochMillis = 1_000,
                expiresAtEpochMillis = 2_000,
            ),
        )
        val manager = SessionManager(
            storage = storage,
            clock = { 2_001 },
            expiryClockSkewMillis = 0,
        )

        assertIs<SessionState.Invalid>(manager.state.value)
        assertNull(manager.bearerToken())
        assertEquals(
            SessionInvalidReason.EXPIRED,
            assertIs<SessionAccessDecision.Denied>(manager.access()).reason,
        )
    }

    @Test fun terminationClearsPersistedSession() {
        val storage = InMemorySecureStorage().apply {
            saveToken("opaque-access-token-1234")
        }
        val manager = SessionManager(storage, clock = { 100 })

        manager.terminate(SessionTerminationReason.USER_LOGOUT)

        assertNull(storage.readToken())
        assertIs<SessionState.Terminated>(manager.state.value)
    }

    private class InMemorySecureStorage : SecureStorage {
        private var session: StoredSession? = null

        override fun saveToken(token: String) {
            session = StoredSession(token)
        }

        override fun readToken(): String? = session?.accessToken

        override fun saveSession(session: StoredSession) {
            this.session = session
        }

        override fun readSession(): StoredSession? = session

        override fun clear() {
            session = null
        }
    }
}
