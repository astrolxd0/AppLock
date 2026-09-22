package dev.pranav.applock.features.lockscreen.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.goHome
import dev.pranav.applock.services.AppLockManager

/**
 * Lock screen presenter for the usage-stats and Shizuku backends.
 *
 * Prefers the overlay window ([LockScreenOverlayManager]) because an Activity can be sidelined
 * by split-screen or floating windows. Falls back to [PasswordOverlayActivity] only when the
 * "display over other apps" permission has been revoked.
 */
class LockScreenHost(private val context: Context) {

    companion object {
        private const val TAG = "LockScreenHost"
    }

    private var overlayManager: LockScreenOverlayManager? = null

    val isShowing: Boolean
        get() = overlayManager?.isShowing == true || AppLockManager.isLockScreenShown.get()

    fun showLockScreen(lockedPackage: String, triggeringPackage: String) {
        // Re-checked on every lock: the permission can be revoked while the service runs,
        // and adding an overlay window without it would silently leave the app unlocked.
        if (!LockScreenOverlayManager.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission missing, falling back to lock activity")
            overlayManager?.removeOverlay()
            startLockActivity(lockedPackage, triggeringPackage)
            return
        }

        val manager = overlayManager ?: LockScreenOverlayManager.forService(context)
            ?.also { overlayManager = it }

        if (manager == null) {
            startLockActivity(lockedPackage, triggeringPackage)
            return
        }

        LogUtils.d(TAG, "Showing lock overlay for $lockedPackage")
        manager.showOverlay(
            lockedPackageName = lockedPackage,
            triggeringPackageName = triggeringPackage,
            onUnlock = { AppLockManager.unlockApp(lockedPackage) },
            onExit = { context.goHome() }
        )
    }

    /** Dismisses the overlay if the user left the locked app. */
    fun onForegroundPackageChanged(packageName: String) {
        overlayManager?.onForegroundPackageChanged(packageName)
    }

    fun dismiss() {
        overlayManager?.removeOverlay()
    }

    fun destroy() {
        overlayManager?.destroy()
        overlayManager = null
    }

    private fun startLockActivity(lockedPackage: String, triggeringPackage: String) {
        AppLockManager.isLockScreenShown.set(true)
        val intent = Intent(context, PasswordOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_FROM_BACKGROUND or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("locked_package", lockedPackage)
            putExtra("triggering_package", triggeringPackage)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting lock activity for $lockedPackage", e)
            AppLockManager.isLockScreenShown.set(false)
        }
    }
}
