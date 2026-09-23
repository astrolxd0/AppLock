package dev.pranav.applock.shizuku

import android.app.ActivityManager
import android.app.IActivityTaskManager
import android.app.TaskInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.IWindowManager
import androidx.annotation.RequiresApi
import dev.pranav.applock.core.broadcast.DeviceUnlockReceiver
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.services.isDeviceLocked
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

class ShizukuActivityManager(
    private val context: Context,
    private val appLockRepository: AppLockRepository,
    private val onForegroundAppChanged: (String, String, Long) -> Unit
) {
    private val TAG = "ShizukuActivityManager"
    private var lastForegroundApp = ""
    private var deviceUnlockReceiver: DeviceUnlockReceiver? = null
    private var shouldLockAppsOnReturn = false
    private var receiversRegistered = false
    private var running = false

    private val handler = Handler(Looper.getMainLooper())
    private val checkForegroundRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                checkForegroundApp()
            } catch (e: Exception) {
                LogUtils.e(TAG, "Unhandled exception in foreground monitor", e)
            } finally {
                // Schedule itself again regardless of failure
                if (running) handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private val homeButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val currentTop = topActivity
                    if (currentTop != null && lastForegroundApp == currentTop.packageName && currentTop.className == "com.android.launcher3.uioverrides.QuickstepLauncher") {
                        AppLockManager.clearTemporarilyUnlockedApp()
                    }
                }

                Intent.ACTION_SCREEN_OFF -> {
                    AppLockManager.clearTemporarilyUnlockedApp()
                    shouldLockAppsOnReturn = true
                    lastForegroundApp = ""
                }

                Intent.ACTION_USER_PRESENT -> {
                    shouldLockAppsOnReturn = true
                }
            }
        }
    }

    fun start(): Boolean {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_DENIED) {
            Log.e(TAG, "Shizuku is not available")
            return false
        }

        try {
            registerEventReceivers()
            startForegroundAppMonitoring()
            return true
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to start Shizuku monitoring", e)
            return false
        }
    }

    private fun registerEventReceivers() {
        if (receiversRegistered) return

        val homeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        context.registerReceiver(homeButtonReceiver, homeFilter, RECEIVER_EXPORTED)

        val unlockFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        deviceUnlockReceiver = DeviceUnlockReceiver {
            shouldLockAppsOnReturn = true
        }
        context.registerReceiver(deviceUnlockReceiver, unlockFilter)
        receiversRegistered = true
    }

    val windowManager: IWindowManager
        get() = SystemServiceHelper.getSystemService("window")
            .let(::ShizukuBinderWrapper)
            .let(IWindowManager.Stub::asInterface)

    private fun startForegroundAppMonitoring() {
        running = true
        handler.removeCallbacks(checkForegroundRunnable)
        handler.post(checkForegroundRunnable)
        Log.d(TAG, "Foreground app monitoring started")
    }

    private fun checkForegroundApp() {
        if (!appLockRepository.isProtectEnabled()) return
        if (appLockRepository.getBackendImplementation() != BackendImplementation.SHIZUKU) {
            running = false
            handler.removeCallbacks(checkForegroundRunnable)
            return
        }

        if (!Shizuku.pingBinder()) {
            LogUtils.e(TAG, "Shizuku binder lost during foreground monitoring")
            return
        }

        if (context.isDeviceLocked()) return

        val visibleTasks = getVisibleTasks()
        if (visibleTasks.isEmpty()) return

        // "Leaving" an app means it is no longer on screen, not merely losing focus. In
        // split-screen or pop-up view the other pane / the launcher takes focus on every touch
        // while the unlocked app stays fully visible; re-locking there is just noise. Once the
        // app has actually gone (home, recents, another fullscreen app) the unlock is dropped
        // so the next launch asks again. Our own lock-screen activities do not count.
        val visiblePackages = visibleTasks.mapNotNull { it.topActivity?.packageName }.toSet()
        val unlockedApp = AppLockManager.temporarilyUnlockedApp
        if (unlockedApp.isNotEmpty() &&
            unlockedApp !in visiblePackages &&
            context.packageName !in visiblePackages
        ) {
            LogUtils.d(TAG, "Unlocked app $unlockedApp left the screen, re-arming its lock")
            AppLockManager.clearTemporarilyUnlockedApp()
        }

        // In split-screen / freeform more than one task is visible. A locked app that is
        // on screen must be locked even if the other pane currently has focus, so prefer any
        // visible locked-and-not-unlocked task over the merely focused one.
        val lockedApps = appLockRepository.getLockedApps()
        val task = visibleTasks.firstOrNull { info ->
            val pkg = info.topActivity?.packageName ?: return@firstOrNull false
            pkg in lockedApps && !AppLockManager.isAppTemporarilyUnlocked(pkg) &&
                    !AppLockManager.appUnlockTimes.containsKey(pkg)
        } ?: visibleTasks.first()

        val activity = task.topActivity ?: return
        val packageName = activity.packageName
        val className = activity.className

        // Skip our own app (lock screen fallback activity etc.)
        if (packageName == context.packageName) return

        // Skip if the app is temporarily unlocked (it is still on screen, see above)
        if (AppLockManager.isAppTemporarilyUnlocked(packageName)) {
            lastForegroundApp = packageName
            return
        }

        // If we should lock apps on return (home button pressed, device locked, etc.)
        // then trigger app lock for any new foreground app
        if (shouldLockAppsOnReturn && packageName != lastForegroundApp) {
            LogUtils.d(TAG, "Should lock apps on return - triggering for: $packageName")
            shouldLockAppsOnReturn = false // Reset the flag

            lastForegroundApp = packageName
            onForegroundAppChanged(packageName, className, System.currentTimeMillis())
            return
        }

        // Normal app switching - check whether the previous app is a trigger exclusion
        if (packageName != lastForegroundApp) {
            val triggerExclusions = appLockRepository.getTriggerExcludedApps()

            if (lastForegroundApp in triggerExclusions) {
                LogUtils.d(
                    TAG,
                    "Previous app $lastForegroundApp is excluded, skipping app lock for $packageName"
                )
                lastForegroundApp = packageName
                return
            }
            LogUtils.d(TAG, "Foreground app changed to: $packageName, class: $className")
        }

        lastForegroundApp = packageName
        onForegroundAppChanged(packageName, className, System.currentTimeMillis())
    }

    fun stop() {
        running = false
        handler.removeCallbacks(checkForegroundRunnable)

        if (receiversRegistered) {
            try {
                context.unregisterReceiver(homeButtonReceiver)
                Log.d(TAG, "Home button receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering home button receiver", e)
            }

            deviceUnlockReceiver?.let { receiver ->
                try {
                    context.unregisterReceiver(receiver)
                    Log.d(TAG, "Device unlock receiver unregistered")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering device unlock receiver", e)
                }
            }
            deviceUnlockReceiver = null
            receiversRegistered = false
        }

        Log.d(TAG, "ShizukuActivityManager stopped")
    }

    companion object {
        private const val POLL_INTERVAL_MS = 400L
    }
}

val topActivity: ComponentName?
    get() = getVisibleTasks().firstOrNull()?.topActivity

private val activityTaskManager: IActivityTaskManager by lazy {
    SystemServiceHelper.getSystemService("activity_task")
        .let(::ShizukuBinderWrapper)
        .let(IActivityTaskManager.Stub::asInterface)
}

/**
 * Which `IActivityTaskManager.getTasks` overload this device has. The signature changed in
 * Android 12 and again mid-Android 13, so the wrong one throws [NoSuchMethodError]. Remembered
 * after the first successful call.
 */
@Volatile
private var getTasksVariant = -1

private fun getTasksWrapper(): List<ActivityManager.RunningTaskInfo> {
    val attempts: List<() -> List<ActivityManager.RunningTaskInfo>?> = listOf(
        { activityTaskManager.getTasks(MAX_TASKS, false, false, Display.INVALID_DISPLAY) },
        { activityTaskManager.getTasks(MAX_TASKS, false, false) },
        { activityTaskManager.getTasks(MAX_TASKS) }
    )

    val order = if (getTasksVariant >= 0) listOf(getTasksVariant) else attempts.indices.toList()

    for (index in order) {
        try {
            val result = attempts[index]() ?: emptyList()
            getTasksVariant = index
            return result
        } catch (_: NoSuchMethodError) {
            // Try the next signature
        } catch (_: AbstractMethodError) {
            // Try the next signature
        } catch (e: Throwable) {
            Log.e("ShizukuActivityManager", "getTasks failed", e)
            return emptyList()
        }
    }
    Log.e("ShizukuActivityManager", "No compatible IActivityTaskManager.getTasks signature found")
    return emptyList()
}

private const val MAX_TASKS = 8

/**
 * The tasks currently on screen, focused task first and then in z-order. Before Android 12
 * the visibility fields do not exist, so only the most recent task is returned.
 */
private fun getVisibleTasks(): List<ActivityManager.RunningTaskInfo> {
    val tasks = getTasksWrapper()
    if (tasks.isEmpty()) return emptyList()

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return listOfNotNull(tasks.firstOrNull { it.topActivity != null })
    }

    val visible = tasks.filter { it.topActivity != null && it.isRunningCompat() && it.isVisibleCompat() }
    if (visible.isEmpty()) return emptyList()

    val focused = visible.firstOrNull { it.isFocused() } ?: return visible
    return listOf(focused) + visible.filter { it !== focused }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun TaskInfo.isRunningCompat(): Boolean = try {
    isRunning
} catch (_: Throwable) {
    true
}

@RequiresApi(Build.VERSION_CODES.S)
private fun TaskInfo.isVisibleCompat(): Boolean = try {
    isVisible
} catch (_: Throwable) {
    true
}

@RequiresApi(Build.VERSION_CODES.Q)
fun TaskInfo.isFreeform(): Boolean {
    try {
        return HiddenApiBypass.invoke(TaskInfo::class.java, this, "isFreeform") as Boolean
    } catch (e: Throwable) {
        return false
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
fun TaskInfo.isFocused(): Boolean {
    try {
        return HiddenApiBypass.getInstanceFields(TaskInfo::class.java)
            .firstOrNull { it.name == "isFocused" }
            ?.getBoolean(this) ?: false
    } catch (e: Throwable) {
        return false
    }
}
