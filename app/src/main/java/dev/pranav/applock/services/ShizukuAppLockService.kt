package dev.pranav.applock.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.pranav.applock.MainActivity
import dev.pranav.applock.R
import dev.pranav.applock.core.broadcast.DeviceAdmin
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.AppLockRepository.Companion.shouldStartService
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.features.lockscreen.ui.LockScreenHost
import dev.pranav.applock.shizuku.ShizukuActivityManager
import rikka.shizuku.Shizuku

class ShizukuAppLockService : Service() {
    private val appLockRepository: AppLockRepository by lazy { applicationContext.appLockRepository() }
    private var shizukuActivityManager: ShizukuActivityManager? = null
    private var previousForegroundPackage = ""
    private val lockScreenHost: LockScreenHost by lazy { LockScreenHost(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    /**
     * The Shizuku binder arrives asynchronously through the ShizukuProvider, usually a moment
     * after our process starts (e.g. right after boot). Instead of giving up when it is not
     * there yet, the service stays alive and starts monitoring as soon as it shows up.
     */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        LogUtils.d(TAG, "Shizuku binder received")
        mainHandler.post { startMonitoringIfReady() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died, waiting for it to come back")
        mainHandler.post {
            stopMonitoring()
            updateNotification(getString(R.string.shizuku_service_waiting))
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                mainHandler.post { startMonitoringIfReady() }
            }
        }

    companion object {
        private const val TAG = "ShizukuAppLockService"
        private const val NOTIFICATION_ID = 112
        private const val CHANNEL_ID = "ShizukuAppLockServiceChannel"

        @Volatile
        var isServiceRunning = false

        @Volatile
        var isMonitoring = false
    }

    override fun onCreate() {
        super.onCreate()
        AppLockManager.isLockScreenShown.set(false)
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtils.d(TAG, "ShizukuAppLockService started. Running: $isServiceRunning")

        if (!shouldStartService(appLockRepository, this::class.java)) {
            Log.e(TAG, "Service not needed. Stopping service.")
            isServiceRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (isServiceRunning) {
            startMonitoringIfReady()
            return START_STICKY
        }
        isServiceRunning = true

        appLockRepository.setActiveBackend(BackendImplementation.SHIZUKU)
        AppLockManager.stopAllOtherServices(this, this::class.java)

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Shizuku listeners", e)
        }

        startMonitoringIfReady()
        return START_STICKY
    }

    override fun onDestroy() {
        LogUtils.d(TAG, "ShizukuAppLockService killed.")

        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister Shizuku listeners", e)
        }

        stopMonitoring()
        lockScreenHost.destroy()

        isServiceRunning = false
        AppLockManager.isLockScreenShown.set(false)
        notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (shouldStartService(appLockRepository, this::class.java)) {
            try {
                val startIntent = Intent(this, ShizukuAppLockService::class.java)
                ContextCompat.startForegroundService(this, startIntent)
                LogUtils.d(TAG, "Re-started ShizukuAppLockService after task removal")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service after task removal", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku state check failed", e)
            false
        }
    }

    private fun startMonitoringIfReady() {
        if (!isServiceRunning || isMonitoring) return

        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "Shizuku binder not available yet, waiting")
            updateNotification(getString(R.string.shizuku_service_waiting))
            return
        }

        if (!isShizukuReady()) {
            Log.w(TAG, "Shizuku permission not granted, waiting")
            updateNotification(getString(R.string.shizuku_service_permission_missing))
            return
        }

        if (shizukuActivityManager == null) {
            setupShizukuActivityManager()
        }

        val started = shizukuActivityManager?.start() == true
        if (!started) {
            Log.e(TAG, "Shizuku monitoring failed to start")
            updateNotification(getString(R.string.shizuku_service_waiting))
            return
        }

        isMonitoring = true
        previousForegroundPackage = ""
        updateNotification(getString(R.string.shizuku_service_protecting))
        LogUtils.d(TAG, "Shizuku monitoring started")
    }

    private fun stopMonitoring() {
        if (!isMonitoring && shizukuActivityManager == null) return
        shizukuActivityManager?.stop()
        shizukuActivityManager = null
        isMonitoring = false
        lockScreenHost.dismiss()
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification(getString(R.string.shizuku_service_waiting))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = determineForegroundServiceType()
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun determineForegroundServiceType(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = ComponentName(this, DeviceAdmin::class.java)

            return if (dpm.isAdminActive(component)) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        }
        return 0
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "AppLock Service",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AppLock")
            .setContentText(text)
            .setContentIntent(openApp)
            .setSmallIcon(R.drawable.baseline_shield_24)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            notificationManager.notify(NOTIFICATION_ID, createNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    private fun setupShizukuActivityManager() {
        shizukuActivityManager =
            ShizukuActivityManager(this, appLockRepository) { packageName, _, timeMillis ->
                if (packageName == this.packageName) {
                    return@ShizukuActivityManager
                }

                val triggeringPackage = previousForegroundPackage
                previousForegroundPackage = packageName

                // Dismiss a lock screen the user has navigated away from.
                lockScreenHost.onForegroundPackageChanged(packageName)

                if (AppLockManager.isLockScreenShown.get()) {
                    return@ShizukuActivityManager
                }

                val triggerExclusions = appLockRepository.getTriggerExcludedApps()
                if (triggeringPackage in triggerExclusions) {
                    LogUtils.d(
                        TAG,
                        "Trigger app $triggeringPackage is excluded, skipping lock for $packageName"
                    )
                    return@ShizukuActivityManager
                }

                LogUtils.d(TAG, "Current package=$packageName, trigger=$triggeringPackage")
                checkAndLockApp(packageName, triggeringPackage, timeMillis)
            }
    }

    private fun checkAndLockApp(packageName: String, triggeringPackage: String, currentTime: Long) {
        val lockedApps = appLockRepository.getLockedApps()

        if (packageName !in lockedApps) return

        val unlockDurationMinutes = appLockRepository.getUnlockTimeDuration()
        val unlockTimestamp = AppLockManager.appUnlockTimes[packageName] ?: 0L

        LogUtils.d(
            TAG,
            "checkAndLockApp: pkg=$packageName, duration=$unlockDurationMinutes min, unlockTime=$unlockTimestamp, currentTime=$currentTime, isLockScreenShown=${AppLockManager.isLockScreenShown.get()}"
        )

        if (unlockDurationMinutes > 0 && unlockTimestamp > 0) {
            if (unlockDurationMinutes >= 10_000) {
                return
            }

            val durationMillis = unlockDurationMinutes.toLong() * 60_000L

            val elapsedMillis = currentTime - unlockTimestamp

            LogUtils.d(
                TAG,
                "Grace period check: elapsed=${elapsedMillis}ms (${elapsedMillis / 1000}s), duration=${durationMillis}ms (${durationMillis / 1000}s)"
            )

            if (elapsedMillis < durationMillis) {
                return
            }

            LogUtils.d(TAG, "Unlock grace period expired for $packageName. Clearing timestamp.")
            AppLockManager.appUnlockTimes.remove(packageName)
        }

        if (AppLockManager.isLockScreenShown.get() ||
            AppLockManager.currentBiometricState == AppLockAccessibilityService.BiometricState.AUTH_STARTED
        ) {
            LogUtils.d(TAG, "Lock screen already shown or biometric auth in progress, skipping")
            return
        }

        LogUtils.d(TAG, "Locked app detected: $packageName. Showing lock screen.")
        AppLockManager.isLockScreenShown.set(true)
        lockScreenHost.showLockScreen(packageName, triggeringPackage)
    }
}
