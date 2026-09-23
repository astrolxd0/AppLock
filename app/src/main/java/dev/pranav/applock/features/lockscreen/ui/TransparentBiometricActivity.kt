package dev.pranav.applock.features.lockscreen.ui

import android.os.Bundle
import android.util.Log
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import dev.pranav.applock.R
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.ui.theme.AppLockTheme
import java.lang.ref.WeakReference

/**
 * Opaque activity whose only job is to host the AndroidX biometric prompt.
 *
 * Used when the framework prompt cannot be shown straight from the service. Some OEM builds
 * (Samsung One UI hosts its biometric UI in a separate package) cancel a prompt whose caller
 * is not the top task; this activity makes our own package the top task for the duration.
 *
 * When a [resultListener] is registered the outcome is handed to it and nothing else is
 * touched; otherwise the activity behaves like the old stand-alone flow and unlocks the app
 * itself.
 */
class TransparentBiometricActivity : FragmentActivity() {
    private val TAG = "TransparentBiometric"
    private var lockedPackageName: String? = null
    private var resultDelivered = false

    companion object {
        const val EXTRA_LOCKED_PACKAGE = "locked_package"
        const val EXTRA_APP_NAME = "app_name"

        /** Error code reported to [resultListener] when the prompt could not be started. */
        const val ERROR_FAILED_TO_START = -1

        /** Receives (success, errorCode). errorCode is 0 on success. */
        @Volatile
        var resultListener: ((success: Boolean, errorCode: Int) -> Unit)? = null

        /**
         * Invoked once the activity has drawn its first frame. The lock overlay waits for this
         * before hiding itself so the locked app never peeks through in between.
         */
        @Volatile
        var onShownListener: (() -> Unit)? = null

        private var current: WeakReference<TransparentBiometricActivity>? = null

        /** Closes the currently showing instance, if any, without reporting a result. */
        fun cancelCurrent() {
            resultListener = null
            onShownListener = null
            current?.get()?.let {
                if (!it.isFinishing) it.finish()
            }
            current = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        lockedPackageName = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: getString(R.string.this_app)

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Same visuals as the overlay's biometric backdrop so the hand-off is seamless.
        val appIcon = lockedPackageName?.let { pkg ->
            try {
                packageManager.getApplicationIcon(pkg).toBitmap().asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }

        setContent {
            AppLockTheme {
                BiometricBackdropScreen(
                    appName = appName,
                    appIcon = appIcon,
                    statusText = null,
                    promptActive = true,
                    onRetry = {},
                    onUseCredential = {},
                    onClose = {}
                )
            }
        }

        notifyShownAfterFirstDraw()

        AppLockManager.reportBiometricAuthStarted()

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.w(TAG, "Biometric error $errorCode: $errString")
                    AppLockManager.reportBiometricAuthFinished()
                    deliver(false, errorCode)
                    finish()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    AppLockManager.reportBiometricAuthFinished()
                    if (!deliver(true, 0)) {
                        // Stand-alone mode: unlock the app ourselves.
                        AppLockManager.isLockScreenShown.set(false)
                        lockedPackageName?.let { AppLockManager.temporarilyUnlockAppWithBiometrics(it) }
                    }
                    finish()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_app_title, appName))
            .setSubtitle(getString(R.string.confirm_biometric_subtitle))
            .setNegativeButtonText(getString(R.string.use_pin_button))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
            )
            .setConfirmationRequired(false)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Biometric failed to start", e)
            AppLockManager.reportBiometricAuthFinished()
            deliver(false, ERROR_FAILED_TO_START)
            finish()
        }
    }

    private fun notifyShownAfterFirstDraw() {
        val decor = window.decorView
        val observer = decor.viewTreeObserver
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                // Cannot remove a draw listener from within onDraw; do it on the next message.
                decor.post {
                    try {
                        decor.viewTreeObserver.removeOnDrawListener(this)
                    } catch (_: Exception) {
                    }
                    val shown = onShownListener
                    onShownListener = null
                    shown?.invoke()
                }
            }
        }
        observer.addOnDrawListener(listener)
    }

    /** Hands the result to the registered listener. Returns false if there was none. */
    private fun deliver(success: Boolean, errorCode: Int): Boolean {
        if (resultDelivered) return true
        resultDelivered = true
        val listener = resultListener ?: return false
        resultListener = null
        listener(success, errorCode)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (current?.get() === this) current = null
        AppLockManager.reportBiometricAuthFinished()
        // Activity went away without a verdict (e.g. killed): tell the lock screen so it
        // can come back instead of staying hidden.
        deliver(false, BiometricPrompt.ERROR_CANCELED)
    }
}
