package com.fortress.app.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fortress.app.R
import java.util.UUID

/**
 * Thin wrapper around [BiometricPrompt]. Anything that hits a trade-execution endpoint
 * must go through [authorize]; the success callback receives a fresh proof token that
 * gets attached to the API request body.
 */
object BiometricAuthenticator {

    sealed interface Result {
        data class Success(val token: String) : Result
        object Cancelled : Result
        data class Error(val code: Int, val message: String) : Result
        object Unavailable : Result
    }

    fun isAvailable(activity: FragmentActivity): Boolean {
        val mgr = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return mgr.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authorize(
        activity: FragmentActivity,
        title: String? = null,
        subtitle: String? = null,
        onResult: (Result) -> Unit
    ) {
        if (!isAvailable(activity)) {
            onResult(Result.Unavailable)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Token is short-lived proof for the backend; the real one will be issued
                // server-side after attestation, this is a placeholder for the scaffold.
                onResult(Result.Success(token = "biom-${UUID.randomUUID()}"))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED) {
                    onResult(Result.Cancelled)
                } else {
                    onResult(Result.Error(errorCode, errString.toString()))
                }
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title ?: activity.getString(R.string.biometric_title))
            .setSubtitle(subtitle ?: activity.getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(info)
    }
}
