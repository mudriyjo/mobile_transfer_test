@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bank.mobile.core.security

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

class IosBiometricAuthenticator : BiometricAuthenticator {
    override suspend fun authenticate(reason: String): BiometricResult = suspendCancellableCoroutine { continuation ->
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)) {
            continuation.resume(BiometricResult.Failure("Biometric authentication is unavailable"))
            return@suspendCancellableCoroutine
        }

        context.evaluatePolicy(
            policy = LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            localizedReason = reason,
        ) { success, error ->
            if (!continuation.isActive) return@evaluatePolicy
            val result = when {
                success -> BiometricResult.Success
                error?.code in listOf(-2L, -4L, -9L) -> BiometricResult.Cancelled
                error?.code == -8L -> BiometricResult.LockedOut
                else -> BiometricResult.Failure(error?.localizedDescription ?: "Authentication failed")
            }
            continuation.resume(result)
        }
        continuation.invokeOnCancellation { context.invalidate() }
    }
}
