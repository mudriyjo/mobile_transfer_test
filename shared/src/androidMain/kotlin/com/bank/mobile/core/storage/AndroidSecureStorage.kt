package com.bank.mobile.core.storage

import android.content.Context

/** SharedPreferences-backed session storage used by the current mobile application. */
class AndroidSecureStorage(context: Context) : SecureStorage {
    private val preferences = context.getSharedPreferences("mobile_bank", Context.MODE_PRIVATE)

    override fun saveToken(token: String) {
        saveSession(StoredSession(accessToken = token))
    }

    override fun readToken(): String? = preferences.getString(ACCESS_TOKEN, null)

    override fun saveSession(session: StoredSession) {
        preferences.edit()
            .putString(ACCESS_TOKEN, session.accessToken)
            .putLong(ISSUED_AT, session.issuedAtEpochMillis)
            .putString(AUTHENTICATION_METHOD, session.authenticationMethod.name)
            .apply {
                if (session.expiresAtEpochMillis == null) remove(EXPIRES_AT)
                else putLong(EXPIRES_AT, session.expiresAtEpochMillis)
            }
            .apply()
    }

    override fun readSession(): StoredSession? {
        val token = readToken() ?: return null
        val method = preferences.getString(AUTHENTICATION_METHOD, null)
            ?.let { raw -> SessionAuthenticationMethod.entries.firstOrNull { it.name == raw } }
            ?: SessionAuthenticationMethod.UNKNOWN
        return StoredSession(
            accessToken = token,
            issuedAtEpochMillis = preferences.getLong(ISSUED_AT, 0),
            expiresAtEpochMillis = if (preferences.contains(EXPIRES_AT)) {
                preferences.getLong(EXPIRES_AT, 0)
            } else {
                null
            },
            authenticationMethod = method,
        )
    }

    override fun clear() {
        preferences.edit()
            .remove(ACCESS_TOKEN)
            .remove(ISSUED_AT)
            .remove(EXPIRES_AT)
            .remove(AUTHENTICATION_METHOD)
            .apply()
    }

    private companion object {
        const val ACCESS_TOKEN = "access_token"
        const val ISSUED_AT = "session_issued_at"
        const val EXPIRES_AT = "session_expires_at"
        const val AUTHENTICATION_METHOD = "session_authentication_method"
    }
}
