package com.bank.mobile.core.security

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidBiometricAuthenticator(
    private val activity: FragmentActivity,
) : BiometricAuthenticator {
    override suspend fun authenticate(reason: String): BiometricResult = suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(BiometricResult.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.isActive) return
                    val result = when (errorCode) {
                        BiometricPrompt.ERROR_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        -> BiometricResult.Cancelled

                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                        -> BiometricResult.LockedOut

                        else -> BiometricResult.Failure(errString.toString())
                    }
                    continuation.resume(result)
                }

                override fun onAuthenticationFailed() = Unit
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm transfer")
            .setSubtitle(reason)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(promptInfo)
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}
