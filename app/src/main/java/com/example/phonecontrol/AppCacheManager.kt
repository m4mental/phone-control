package com.example.phonecontrol

import android.content.Context
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

/**
 * High-Speed In-Memory Application Cache.
 * Pre-warms and caches installed app metadata, icons, and package details
 * so that screens like App Extractor, App Freezer, and App Selectors load
 * instantaneously in 0ms without repeated slow PackageManager queries.
 */
object AppCacheManager {

    @Volatile
    var cachedUserApps: List<AppExtractorManager.AppItem>? = null

    @Volatile
    var cachedAllApps: List<AppExtractorManager.AppItem>? = null

    @Volatile
    var cachedSystemApps: List<AppExtractorManager.AppItem>? = null

    // Per-package icon and label cache for fast recyclerview binding
    val iconCache = ConcurrentHashMap<String, Drawable>()
    val labelCache = ConcurrentHashMap<String, String>()

    private var lastCacheTimestamp = 0L
    private const val CACHE_EXPIRY_MS = 15 * 60 * 1000L // 15 minutes

    fun isCacheValid(): Boolean {
        return cachedUserApps != null && (System.currentTimeMillis() - lastCacheTimestamp < CACHE_EXPIRY_MS)
    }

    @Synchronized
    fun getOrLoadUserApps(context: Context, forceRefresh: Boolean = false): List<AppExtractorManager.AppItem> {
        if (!forceRefresh && isCacheValid() && cachedUserApps != null) {
            return cachedUserApps!!
        }
        val apps = AppExtractorManager.getInstalledApps(context, includeSystem = false)
        cachedUserApps = apps
        lastCacheTimestamp = System.currentTimeMillis()
        return apps
    }

    @Synchronized
    fun getOrLoadAllApps(context: Context, forceRefresh: Boolean = false): List<AppExtractorManager.AppItem> {
        if (!forceRefresh && isCacheValid() && cachedAllApps != null) {
            return cachedAllApps!!
        }
        val apps = AppExtractorManager.getInstalledApps(context, includeSystem = true)
        cachedAllApps = apps
        cachedUserApps = apps.filter { !it.isSystemApp }
        cachedSystemApps = apps.filter { it.isSystemApp }
        lastCacheTimestamp = System.currentTimeMillis()
        return apps
    }

    @Synchronized
    fun invalidate() {
        cachedUserApps = null
        cachedAllApps = null
        cachedSystemApps = null
        iconCache.clear()
        labelCache.clear()
        lastCacheTimestamp = 0L
    }
}
