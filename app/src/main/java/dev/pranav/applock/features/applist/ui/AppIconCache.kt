package dev.pranav.applock.features.applist.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

object AppIconCache {
    // Icons are drawn at <= 48dp, so there's no point rasterising adaptive icons at their full
    // intrinsic size (often 400px+ and ~700KB each). 144px covers 48dp at xxhdpi.
    private const val MAX_ICON_SIZE_PX = 144
    private const val MAX_LABEL_CACHE_SIZE = 1000

    private val iconCache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.asAndroidBitmap().allocationByteCount
    }
    private val labelCache = LruCache<String, String>(MAX_LABEL_CACHE_SIZE)

    fun getCachedIcon(packageName: String): ImageBitmap? = iconCache.get(packageName)

    fun getCachedLabel(packageName: String): String? = labelCache.get(packageName)

    fun getIcon(context: Context, appInfo: ApplicationInfo): ImageBitmap? {
        val cached = iconCache.get(appInfo.packageName)
        if (cached != null) return cached

        val drawable = appInfo.loadIcon(context.packageManager) ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_SIZE_PX)
            ?: MAX_ICON_SIZE_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_SIZE_PX)
            ?: MAX_ICON_SIZE_PX
        val icon = drawable.toBitmap(width, height).asImageBitmap()
        iconCache.put(appInfo.packageName, icon)
        return icon
    }

    fun getLabel(context: Context, appInfo: ApplicationInfo): String {
        val cached = labelCache.get(appInfo.packageName)
        if (cached != null) return cached

        val label = appInfo.loadLabel(context.packageManager).toString()
        labelCache.put(appInfo.packageName, label)
        return label
    }

    fun putLabel(packageName: String, label: String) {
        labelCache.put(packageName, label)
    }

    fun clear() {
        iconCache.evictAll()
        labelCache.evictAll()
    }
}
