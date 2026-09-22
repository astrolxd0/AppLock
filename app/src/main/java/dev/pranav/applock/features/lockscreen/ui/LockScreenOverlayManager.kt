package dev.pranav.applock.features.lockscreen.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.biometrics.BiometricPrompt
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.pranav.applock.R
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.features.lockscreen.biometric.SystemBiometricPrompt
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.ui.theme.AppLockTheme

/**
 * Shows the lock screen as a real system overlay window instead of an Activity.
 *
 * An overlay window is drawn above every app window on the display, including apps in
 * split-screen, freeform / pop-up windows and picture-in-picture. An Activity, by contrast,
 * is placed into one split pane or behind a floating window, leaving the locked app fully
 * usable. This manager is shared by all three backends:
 *
 *  - Accessibility service: [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]. This is
 *    the strongest option; it is above the biometric prompt and immune to apps that hide
 *    overlay windows with `Window.setHideOverlayWindows`.
 *  - Usage stats / Shizuku services: [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY],
 *    which requires the "display over other apps" permission the onboarding already asks for.
 *
 * Biometrics are handled in place with the framework [BiometricPrompt] (see
 * [SystemBiometricPrompt]) so the overlay never has to be torn down. Because an accessibility
 * overlay would cover the system prompt, a temporary opaque "shield" application overlay is
 * shown (and the accessibility view hidden) for the duration of the prompt.
 */
class LockScreenOverlayManager private constructor(
    private val hostContext: Context,
    private val windowType: Int
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {

    companion object {
        private const val TAG = "LockScreenOverlay"
        private const val AUTO_PROMPT_DELAY_MS = 250L
        private const val PROMPT_RETRY_DELAY_MS = 600L
        private const val MAX_PROMPT_RETRIES = 2

        /**
         * Some OEM builds (Samsung One UI) cancel a biometric prompt whose caller is not the
         * top task, so a prompt started from a service dies with BIOMETRIC_ERROR_CANCELED about
         * a second after it appears. Once that has been observed the prompt is hosted in
         * [TransparentBiometricActivity] for the rest of the process lifetime. Samsung devices
         * start out in that mode so the first lock does not waste a doomed attempt.
         */
        @Volatile
        private var useActivityHostedPrompt: Boolean =
            Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        fun forAccessibilityService(service: AccessibilityService): LockScreenOverlayManager =
            LockScreenOverlayManager(
                service,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            )

        /**
         * Overlay host for the usage-stats and Shizuku services. Returns null when the
         * "display over other apps" permission is missing; callers then fall back to
         * [PasswordOverlayActivity].
         */
        fun forService(context: Context): LockScreenOverlayManager? {
            if (!canDrawOverlays(context)) return null
            return LockScreenOverlayManager(
                context,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            )
        }

        fun canDrawOverlays(context: Context): Boolean = try {
            Settings.canDrawOverlays(context)
        } catch (_: Exception) {
            false
        }

        /**
         * A context whose WindowManager adds windows with a fresh
         * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] token. Using the accessibility
         * service context directly would attach the window to the accessibility overlay token
         * and place it in the accessibility z-order, which is exactly what we do not want here.
         */
        private fun createApplicationOverlayContext(context: Context): Context {
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return app

            val display = app.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return app
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    app.createWindowContext(
                        display,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        null
                    )
                } else {
                    app.createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "createWindowContext failed, using application context", e)
                app
            }
        }
    }

    private val isAccessibilityOverlay =
        windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    /** Context + WindowManager used for the main lock window. */
    private val mainWindowContext: Context =
        if (isAccessibilityOverlay) hostContext else createApplicationOverlayContext(hostContext)
    private val mainWindowManager: WindowManager =
        mainWindowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Lazily created context + WindowManager for the biometric shield window. */
    private var shieldWindowContext: Context? = null
    private var shieldWindowManager: WindowManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appLockRepository by lazy { hostContext.appLockRepository() }

    private val biometricPrompt: SystemBiometricPrompt? =
        if (SystemBiometricPrompt.isSupported()) SystemBiometricPrompt(hostContext.applicationContext) else null

    // Lifecycle plumbing for Compose
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private var isStateRestored = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val onBackPressedDispatcher = OnBackPressedDispatcher { handleExit() }

    // Window state
    private var rootView: OverlayRootView? = null
    private var shieldView: ComposeView? = null

    // Lock target
    var lockedPackageName: String? = null
        private set
    private var triggeringPackageName: String? = null
    private var lockedAppName: String = ""
    private var lockedAppIcon: ImageBitmap? = null
    private var onUnlock: (() -> Unit)? = null
    private var onExit: (() -> Unit)? = null

    // Compose-observable UI state
    private var uiMode by mutableStateOf(LockUiMode.CREDENTIAL_ENTRY)
    private var promptActive by mutableStateOf(false)
    private var biometricStatus by mutableStateOf<String?>(null)
    private var showBiometricButton by mutableStateOf(false)

    private var promptRetries = 0
    private val autoPromptRunnable = Runnable { startBiometricAuth(userInitiated = false) }
    private val retryPromptRunnable = Runnable { startBiometricAuth(userInitiated = false) }

    val isShowing: Boolean
        get() = rootView != null

    /**
     * Shows the lock screen for [lockedPackageName]. Safe to call from any thread and safe to
     * call repeatedly; a call for the package already being shown is a no-op, a call for a
     * different package replaces the current lock screen.
     */
    fun showOverlay(
        lockedPackageName: String,
        triggeringPackageName: String,
        onUnlock: () -> Unit,
        onExit: () -> Unit
    ) {
        AppLockManager.isLockScreenShown.set(true)
        runOnMain {
            if (rootView != null) {
                if (this.lockedPackageName == lockedPackageName) return@runOnMain
                LogUtils.d(TAG, "Retargeting lock screen from ${this.lockedPackageName} to $lockedPackageName")
                detachAll()
            }

            this.lockedPackageName = lockedPackageName
            this.triggeringPackageName = triggeringPackageName
            this.onUnlock = onUnlock
            this.onExit = onExit
            loadAppInfo(lockedPackageName)

            val biometricsEnabled = appLockRepository.isBiometricAuthEnabled() &&
                    SystemBiometricPrompt.canAuthenticate(hostContext)
            val inlinePromptAvailable = biometricsEnabled && biometricPrompt != null

            showBiometricButton = biometricsEnabled
            biometricStatus = null
            promptActive = false
            promptRetries = 0
            uiMode = if (inlinePromptAvailable && appLockRepository.isBiometricFirstEnabled()) {
                LockUiMode.BIOMETRIC_ONLY
            } else {
                LockUiMode.CREDENTIAL_ENTRY
            }

            if (!attachMainWindow()) {
                AppLockManager.isLockScreenShown.set(false)
                return@runOnMain
            }
            AppLockManager.isLockScreenShown.set(true)

            if (inlinePromptAvailable) {
                mainHandler.postDelayed(autoPromptRunnable, AUTO_PROMPT_DELAY_MS)
            }
        }
    }

    /** Removes the lock screen without unlocking anything. Safe to call from any thread. */
    fun removeOverlay() {
        runOnMain { detachAll() }
    }

    /**
     * Called by the backend whenever the foreground app changes. If the user navigated away
     * from the locked app (home, recents, another app) the lock screen is dismissed so it does
     * not hang over whatever is shown now. The backend re-locks as needed on the next launch.
     */
    fun onForegroundPackageChanged(packageName: String) {
        if (!isShowing) return
        if (packageName == lockedPackageName || packageName == hostContext.packageName) return
        if (promptActive || packageName.startsWith("com.samsung.android.biometrics")) {
            // The biometric UI (or our prompt-hosting activity) is what came to the front,
            // not the user leaving the app.
            return
        }
        LogUtils.d(TAG, "Foreground moved to $packageName while locking $lockedPackageName, dismissing")
        removeOverlay()
    }

    fun destroy() {
        runOnMain {
            detachAll()
            store.clear()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Window management
    // ------------------------------------------------------------------------------------------

    private fun ensureLifecycleCreated() {
        if (!isStateRestored) {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            isStateRestored = true
        }
    }

    private fun attachMainWindow(): Boolean {
        ensureLifecycleCreated()

        val composeView = ComposeView(mainWindowContext).apply {
            setContent {
                CompositionLocalProvider(
                    LocalOnBackPressedDispatcherOwner provides this@LockScreenOverlayManager
                ) {
                    AppLockTheme {
                        LockScreenContent(
                            lockedAppName = lockedAppName,
                            appIcon = lockedAppIcon,
                            triggeringPackageName = triggeringPackageName,
                            mode = uiMode,
                            promptActive = promptActive,
                            biometricStatus = biometricStatus,
                            showBiometricButton = showBiometricButton,
                            onPinAttempt = { pin -> attemptCredential(appLockRepository.validatePassword(pin)) },
                            onPatternAttempt = { pattern -> attemptCredential(appLockRepository.validatePattern(pattern)) },
                            onPasswordAttempt = { password -> attemptCredential(appLockRepository.validatePassword(password)) },
                            onBiometricAuth = { startBiometricAuth(userInitiated = true) },
                            onUseCredential = { switchToCredentialEntry() },
                            onClose = { handleExit() }
                        )
                    }
                }
            }
        }

        val root = OverlayRootView(mainWindowContext).apply {
            attachOwners(this)
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        return try {
            mainWindowManager.addView(root, buildLayoutParams(windowType))
            rootView = root
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            LogUtils.d(TAG, "Lock overlay attached for $lockedPackageName (type=$windowType)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add lock overlay window", e)
            false
        }
    }

    private fun attachOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    private fun buildLayoutParams(type: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Cover the whole display, including behind the bars; Compose applies the
                // bar insets itself. Only shrink for the keyboard.
                fitInsetsTypes = WindowInsets.Type.ime()
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            if (appLockRepository.shouldUseMaxBrightness()) {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            }
        }
    }

    private fun detachAll() {
        mainHandler.removeCallbacks(autoPromptRunnable)
        mainHandler.removeCallbacks(retryPromptRunnable)
        biometricPrompt?.cancel()
        TransparentBiometricActivity.cancelCurrent()
        promptActive = false
        removeShield()

        rootView?.let { view ->
            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                mainWindowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing lock overlay", e)
            }
            LogUtils.d(TAG, "Lock overlay removed for $lockedPackageName")
        }
        rootView = null
        lockedPackageName = null
        triggeringPackageName = null
        onUnlock = null
        onExit = null
        AppLockManager.isLockScreenShown.set(false)
    }

    // ------------------------------------------------------------------------------------------
    // Credential / exit handling
    // ------------------------------------------------------------------------------------------

    private fun attemptCredential(isValid: Boolean): Boolean {
        if (isValid) handleUnlock()
        return isValid
    }

    private fun handleUnlock() {
        val callback = onUnlock
        LogUtils.d(TAG, "Unlocked $lockedPackageName")
        detachAll()
        callback?.invoke()
    }

    private fun handleExit() {
        val callback = onExit
        LogUtils.d(TAG, "Lock screen exited without unlocking $lockedPackageName")
        detachAll()
        callback?.invoke()
    }

    private fun switchToCredentialEntry() {
        biometricPrompt?.cancel()
        mainHandler.removeCallbacks(retryPromptRunnable)
        promptActive = false
        removeShield()
        rootView?.visibility = View.VISIBLE
        uiMode = LockUiMode.CREDENTIAL_ENTRY
    }

    // ------------------------------------------------------------------------------------------
    // Biometrics
    // ------------------------------------------------------------------------------------------

    private fun startBiometricAuth(userInitiated: Boolean) {
        if (!isShowing) return
        if (!appLockRepository.isBiometricAuthEnabled()) return
        if (promptActive) return

        val prompt = biometricPrompt
        if (prompt == null || useActivityHostedPrompt) {
            // Either no framework prompt (API < 28) or this device refuses service-side
            // prompts: host it in our own activity while the overlay stays underneath.
            startActivityHostedPrompt(userInitiated)
            return
        }

        if (userInitiated) promptRetries = 0
        biometricStatus = null

        prepareWindowsForPrompt()
        promptActive = true

        val started = prompt.authenticate(
            lockedAppName.ifEmpty { hostContext.getString(R.string.this_app) },
            object : SystemBiometricPrompt.Callback {
                override fun onSucceeded() {
                    promptActive = false
                    LogUtils.d(TAG, "Biometric auth succeeded for $lockedPackageName")
                    handleUnlock()
                }

                override fun onNegativeButton() {
                    promptActive = false
                    restoreWindowsAfterPrompt()
                    uiMode = LockUiMode.CREDENTIAL_ENTRY
                }

                override fun onError(errorCode: Int, message: CharSequence) {
                    promptActive = false
                    handleBiometricError(errorCode, message)
                }
            }
        )

        if (!started) {
            promptActive = false
            restoreWindowsAfterPrompt()
            if (uiMode == LockUiMode.BIOMETRIC_ONLY) {
                uiMode = LockUiMode.CREDENTIAL_ENTRY
            }
        }
    }

    private fun handleBiometricError(errorCode: Int, message: CharSequence) {
        if (!isShowing) return

        when (errorCode) {
            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> {
                // Back / tap outside. In biometrics-first mode that means "never mind":
                // leave the app instead of dropping the user on the PIN pad.
                if (uiMode == LockUiMode.BIOMETRIC_ONLY) {
                    handleExit()
                } else {
                    restoreWindowsAfterPrompt()
                }
            }

            BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> {
                if (!useActivityHostedPrompt) {
                    // The system killed a prompt started from the service. Switch to hosting
                    // it in our own activity, which this device is happy with.
                    useActivityHostedPrompt = true
                    LogUtils.d(TAG, "Service-side biometric prompt cancelled, switching to activity-hosted prompt")
                    startActivityHostedPrompt(userInitiated = false)
                } else if (promptRetries < MAX_PROMPT_RETRIES) {
                    promptRetries++
                    LogUtils.d(TAG, "Biometric prompt cancelled by system, retry $promptRetries")
                    mainHandler.postDelayed(retryPromptRunnable, PROMPT_RETRY_DELAY_MS)
                } else {
                    restoreWindowsAfterPrompt()
                    biometricStatus = hostContext.getString(R.string.biometric_prompt_interrupted)
                }
            }

            else -> {
                restoreWindowsAfterPrompt()
                biometricStatus = message.toString().ifBlank { null }
                if (SystemBiometricPrompt.isTerminalError(errorCode)) {
                    // Lockout / no hardware: biometrics will not work, show the credential UI.
                    uiMode = LockUiMode.CREDENTIAL_ENTRY
                }
            }
        }
    }

    /**
     * Makes sure the system biometric prompt will actually be visible. An accessibility
     * overlay is layered above the prompt, so it is hidden for the duration of the prompt and
     * an opaque application overlay "shield" is shown instead to keep the app's content
     * covered. An application overlay is already below the prompt and needs no changes.
     */
    private fun prepareWindowsForPrompt() {
        if (!isAccessibilityOverlay) return
        if (shieldView == null && canDrawOverlays(hostContext)) {
            attachShield()
        }
        rootView?.visibility = View.INVISIBLE
    }

    private fun restoreWindowsAfterPrompt() {
        removeShield()
        rootView?.visibility = View.VISIBLE
    }

    private fun attachShield() {
        ensureLifecycleCreated()

        val context = shieldWindowContext ?: createApplicationOverlayContext(hostContext).also {
            shieldWindowContext = it
            shieldWindowManager = it.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        val wm = shieldWindowManager ?: return

        val view = ComposeView(context).apply {
            attachOwners(this)
            setContent {
                CompositionLocalProvider(
                    LocalOnBackPressedDispatcherOwner provides this@LockScreenOverlayManager
                ) {
                    AppLockTheme {
                        BiometricBackdropScreen(
                            appName = lockedAppName,
                            appIcon = lockedAppIcon,
                            statusText = null,
                            promptActive = true,
                            onRetry = {},
                            onUseCredential = {},
                            onClose = {}
                        )
                    }
                }
            }
        }

        val params = buildLayoutParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).apply {
            // The shield only has to cover; the prompt takes the input.
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        try {
            wm.addView(view, params)
            shieldView = view
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add biometric shield window", e)
        }
    }

    private fun removeShield() {
        val view = shieldView ?: return
        shieldView = null
        try {
            shieldWindowManager?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing biometric shield", e)
        }
    }

    /**
     * Shows the AndroidX prompt inside [TransparentBiometricActivity]. The lock overlay stays
     * attached but hidden (the activity is opaque and covers the app), and comes back if the
     * prompt does not succeed.
     */
    private fun startActivityHostedPrompt(userInitiated: Boolean) {
        val packageName = lockedPackageName ?: return
        if (userInitiated) promptRetries = 0
        biometricStatus = null

        // The activity sits below any overlay window, so ours must get out of the way.
        removeShield()
        rootView?.visibility = View.INVISIBLE
        promptActive = true

        TransparentBiometricActivity.resultListener = { success, errorCode ->
            runOnMain {
                promptActive = false
                if (!isShowing || lockedPackageName != packageName) return@runOnMain
                if (success) {
                    LogUtils.d(TAG, "Activity-hosted biometric auth succeeded for $packageName")
                    handleUnlock()
                } else {
                    LogUtils.d(TAG, "Activity-hosted biometric auth failed: $errorCode")
                    when (errorCode) {
                        androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            restoreWindowsAfterPrompt()
                            uiMode = LockUiMode.CREDENTIAL_ENTRY
                        }

                        TransparentBiometricActivity.ERROR_FAILED_TO_START -> {
                            restoreWindowsAfterPrompt()
                            uiMode = LockUiMode.CREDENTIAL_ENTRY
                        }

                        else -> handleBiometricError(errorCode, "")
                    }
                }
            }
        }

        val intent = Intent(hostContext, TransparentBiometricActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra(TransparentBiometricActivity.EXTRA_LOCKED_PACKAGE, packageName)
            putExtra(TransparentBiometricActivity.EXTRA_APP_NAME, lockedAppName)
        }
        try {
            hostContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start biometric activity", e)
            TransparentBiometricActivity.resultListener = null
            promptActive = false
            restoreWindowsAfterPrompt()
            if (uiMode == LockUiMode.BIOMETRIC_ONLY) uiMode = LockUiMode.CREDENTIAL_ENTRY
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private fun loadAppInfo(packageName: String) {
        val pm = hostContext.packageManager
        try {
            val info = pm.getApplicationInfo(packageName, 0)
            lockedAppName = pm.getApplicationLabel(info).toString()
            lockedAppIcon = try {
                pm.getApplicationIcon(info).toBitmap().asImageBitmap()
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            lockedAppName = hostContext.getString(R.string.default_app_name)
            lockedAppIcon = null
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * Root of the lock window. A plain window (unlike an Activity) receives KEYCODE_BACK but
     * nobody translates it into anything, so it is handled here: back leaves the locked app.
     */
    private inner class OverlayRootView(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    handleExit()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
}
