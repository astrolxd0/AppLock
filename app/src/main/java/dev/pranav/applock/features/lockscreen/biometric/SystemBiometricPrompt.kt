package dev.pranav.applock.features.lockscreen.biometric

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import dev.pranav.applock.R
import dev.pranav.applock.services.AppLockManager

/**
 * Thin wrapper around the framework [BiometricPrompt] (not the AndroidX one).
 *
 * The AndroidX prompt needs a FragmentActivity, which forced the old lock screen to tear down
 * its overlay window and bounce through a separate activity every time the user tapped the
 * fingerprint button. The framework prompt only needs a Context and can be shown from a
 * foreground service, so the lock overlay can stay exactly where it is while the system
 * prompt is displayed on top of it.
 */
class SystemBiometricPrompt(private val context: Context) {

    interface Callback {
        fun onSucceeded()

        /** The user tapped the negative ("Use PIN") button. */
        fun onNegativeButton()

        /** Any error, including [BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED]. */
        fun onError(errorCode: Int, message: CharSequence)
    }

    private var cancellationSignal: CancellationSignal? = null

    val isActive: Boolean
        get() = cancellationSignal?.isCanceled == false

    fun authenticate(appName: String, callback: Callback): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        cancel()

        val signal = CancellationSignal()
        cancellationSignal = signal
        val executor = ContextCompat.getMainExecutor(context)

        val builder = BiometricPrompt.Builder(context)
            .setTitle(context.getString(R.string.unlock_app_title, appName))
            .setSubtitle(context.getString(R.string.confirm_biometric_subtitle))
            .setNegativeButton(context.getString(R.string.use_pin_button), executor) { _, _ ->
                if (!finish(signal)) return@setNegativeButton
                callback.onNegativeButton()
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setConfirmationRequired(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
        }

        return try {
            builder.build().authenticate(
                signal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        // Errors for a prompt we cancelled ourselves are not interesting.
                        if (!finish(signal)) return
                        Log.w(TAG, "Biometric error $errorCode: $errString")
                        callback.onError(errorCode, errString ?: "")
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        if (!finish(signal)) return
                        callback.onSucceeded()
                    }
                }
            )
            AppLockManager.reportBiometricAuthStarted()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start biometric prompt", e)
            finish(signal)
            false
        }
    }

    /** Dismisses the prompt if it is showing. No callback is delivered for our own cancel. */
    fun cancel() {
        val signal = cancellationSignal ?: return
        cancellationSignal = null
        AppLockManager.reportBiometricAuthFinished()
        if (!signal.isCanceled) {
            try {
                signal.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling biometric prompt", e)
            }
        }
    }

    /**
     * Marks [signal] as finished. Returns false when the signal is stale, i.e. the prompt it
     * belongs to was already replaced or cancelled by us.
     */
    private fun finish(signal: CancellationSignal): Boolean {
        if (signal !== cancellationSignal) return false
        cancellationSignal = null
        AppLockManager.reportBiometricAuthFinished()
        return true
    }

    companion object {
        private const val TAG = "SystemBiometricPrompt"

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        /** True when the device has enrolled biometrics that can be used right now. */
        fun canAuthenticate(context: Context): Boolean {
            return try {
                androidx.biometric.BiometricManager.from(context).canAuthenticate(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
                ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
            } catch (e: Exception) {
                Log.w(TAG, "canAuthenticate failed", e)
                false
            }
        }

        /** Errors after which asking for biometrics again is pointless; fall back to the PIN. */
        fun isTerminalError(errorCode: Int): Boolean = when (errorCode) {
            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
            BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
            BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
            BiometricPrompt.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> true

            else -> false
        }
    }
}
