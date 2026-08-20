@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bank.mobile.core.storage

import platform.Foundation.NSUserDefaults

/** UserDefaults-backed session storage used by the current mobile application. */
class IosSecureStorage : SecureStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun saveToken(token: String) {
        saveSession(StoredSession(accessToken = token))
    }

    override fun readToken(): String? = defaults.stringForKey(ACCESS_TOKEN)

    override fun saveSession(session: StoredSession) {
        defaults.setObject(session.accessToken, forKey = ACCESS_TOKEN)
        defaults.setDouble(session.issuedAtEpochMillis.toDouble(), forKey = ISSUED_AT)
        defaults.setObject(session.authenticationMethod.name, forKey = AUTHENTICATION_METHOD)
        if (session.expiresAtEpochMillis == null) {
            defaults.removeObjectForKey(EXPIRES_AT)
        } else {
            defaults.setDouble(session.expiresAtEpochMillis.toDouble(), forKey = EXPIRES_AT)
        }
    }

    override fun readSession(): StoredSession? {
        val token = readToken() ?: return null
        val method = defaults.stringForKey(AUTHENTICATION_METHOD)
            ?.let { raw -> SessionAuthenticationMethod.entries.firstOrNull { it.name == raw } }
            ?: SessionAuthenticationMethod.UNKNOWN
        return StoredSession(
            accessToken = token,
            issuedAtEpochMillis = defaults.doubleForKey(ISSUED_AT).toLong(),
            expiresAtEpochMillis = if (defaults.objectForKey(EXPIRES_AT) == null) {
                null
            } else {
                defaults.doubleForKey(EXPIRES_AT).toLong()
            },
            authenticationMethod = method,
        )
    }

    override fun clear() {
        defaults.removeObjectForKey(ACCESS_TOKEN)
        defaults.removeObjectForKey(ISSUED_AT)
        defaults.removeObjectForKey(EXPIRES_AT)
        defaults.removeObjectForKey(AUTHENTICATION_METHOD)
    }

    private companion object {
        const val ACCESS_TOKEN = "access_token"
        const val ISSUED_AT = "session_issued_at"
        const val EXPIRES_AT = "session_expires_at"
        const val AUTHENTICATION_METHOD = "session_authentication_method"
    }
}
