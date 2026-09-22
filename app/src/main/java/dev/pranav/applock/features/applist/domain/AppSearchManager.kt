package dev.pranav.applock.features.applist.domain

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Process
import dev.pranav.applock.features.applist.ui.AppIconCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppSearchManager(private val context: Context) {

    suspend fun loadApps(includeSystemApps: Boolean = false): Set<ApplicationInfo> {
        return withContext(Dispatchers.IO) {
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            val apps = if (includeSystemApps) {
                // Load all apps including system apps
                val pm = context.packageManager
                pm.getInstalledApplications(0)
                    .filter { it.packageName != context.packageName }
            } else {
                // Load only user-installed apps with launcher activities
                launcherApps.getActivityList(null, Process.myUserHandle())
                    .mapNotNull { it.applicationInfo }
                    .filter { it.enabled && it.packageName != context.packageName }
            }

            val labels = apps.associateWith { app ->
                app.loadLabel(context.packageManager).toString()
            }
            // Warm the UI label cache so list items don't each hit PackageManager again.
            labels.forEach { (app, label) -> AppIconCache.putLabel(app.packageName, label) }

            val sortedApps = apps.sortedBy { labels[it]?.lowercase() }

            sortedApps.distinctBy { it.packageName }.toSet()
        }
    }
}
