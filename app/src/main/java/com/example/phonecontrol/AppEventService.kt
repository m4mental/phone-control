package com.example.phonecontrol

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Ultra-Fast 0ms Event-Driven App & Window State Listener.
 * Filters out system overlays, keyboards, volume bars, and systemui events to prevent fake app-exit triggers.
 */
class AppEventService : AccessibilityService() {

    private var lastDispatchedPkg = ""

    private val ignoredSystemPackages = setOf(
        "com.android.systemui",
        "android",
        "com.google.android.inputmethod.latin",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.settings.intelligence",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "com.sohu.inputmethod.sogou",
        "com.baidu.input"
    )

    private var lastRecentsCheckTime = 0L
    private var lastForegroundDispatchTime = 0L
    private val launcherPackages = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var lastLauncherQueryTime = 0L

    private fun getPackageForExactUniqueLabel(label: String): String? {
        val now = System.currentTimeMillis()
        if (now - lastLabelCacheTime > 10000 || appLabelToPackageMap.isEmpty()) {
            appLabelToPackageMap.clear()
            val allFrozen = FreezerManager.getSpecialFreezeApps(this) + FreezerManager.getFrozenApps(this)
            val counts = mutableMapOf<String, MutableList<String>>()
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS or android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
            } else {
                @Suppress("DEPRECATION")
                android.content.pm.PackageManager.GET_DISABLED_COMPONENTS or android.content.pm.PackageManager.GET_UNINSTALLED_PACKAGES
            }
            for (pkg in allFrozen) {
                try {
                    val appInfo = packageManager.getApplicationInfo(pkg, flags)
                    val l = packageManager.getApplicationLabel(appInfo).toString().trim().lowercase()
                    if (l.isNotEmpty()) {
                        counts.getOrPut(l) { mutableListOf() }.add(pkg)
                    }
                } catch (_: Exception) {}
            }
            // Retain ONLY exact, unique labels that map unambiguously to exactly one package
            for ((l, pkgs) in counts) {
                if (pkgs.size == 1) {
                    appLabelToPackageMap[l] = pkgs[0]
                }
            }
            lastLabelCacheTime = now
        }
        val clean = label.lowercase().trim()
        return appLabelToPackageMap[clean] // Exact, unique match only!
    }

    private var cachedHomePackage: String? = null

    private fun getKnownHomePackages(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - lastLauncherQueryTime > 60000 || launcherPackages.isEmpty()) {
            launcherPackages.clear()
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(homeIntent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(homeIntent, 0)
                }
                for (info in resolveInfos) {
                    info.activityInfo?.packageName?.let { launcherPackages.add(it) }
                }
            } catch (_: Exception) {}
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val res = packageManager.resolveActivity(homeIntent, 0)
                res?.activityInfo?.packageName?.let {
                    cachedHomePackage = it
                    launcherPackages.add(it)
                }
            } catch (_: Exception) {}
            lastLauncherQueryTime = now
        }
        return launcherPackages
    }

    private fun isLauncherPackage(pkgName: String, clsName: String): Boolean {
        if (pkgName.isBlank()) return false
        val homePkgs = getKnownHomePackages()
        return homePkgs.contains(pkgName) || (cachedHomePackage != null && pkgName == cachedHomePackage)
    }

    private fun dispatchRecentsCheck() {
        val now = System.currentTimeMillis()
        if (now - lastRecentsCheckTime < 200) return
        lastRecentsCheckTime = now
        val intent = Intent(this, AutoTweakService::class.java).apply {
            action = AutoTweakService.ACTION_RECENTS_CHANGED
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e("AppEventService", "Error dispatching recents check: ${e.message}")
        }
    }

    private fun isHomeOrLauncher(pkgName: String, clsName: String): Boolean {
        if (pkgName.isBlank()) return false
        val homePkgs = getKnownHomePackages()
        if (homePkgs.contains(pkgName) || (cachedHomePackage != null && pkgName == cachedHomePackage)) {
            return true
        }
        // Recents / Overview hosted inside SystemUI
        if (pkgName == "com.android.systemui" && (clsName.contains("Recents", ignoreCase = true) || clsName.contains("Overview", ignoreCase = true))) {
            return true
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val pkgName = event.packageName?.toString() ?: ""
        val clsName = event.className?.toString() ?: ""

        // 1. Instant Launcher Click Detection: Pre-register & protect app on click before window transition starts
        // Restricted strictly to launcher packages and exact, unique labels
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isLauncherPackage(pkgName, clsName)) {
            val desc = event.contentDescription?.toString() ?: ""
            val text = event.text?.joinToString(" ") ?: ""
            val combined = if (desc.isNotBlank()) desc else text
            var cleanLabel = combined.trim()
            if (cleanLabel.contains("Disabled ", ignoreCase = true)) {
                cleanLabel = cleanLabel.substringAfter("Disabled ").trim()
            } else if (cleanLabel.contains("Paused ", ignoreCase = true)) {
                cleanLabel = cleanLabel.substringAfter("Paused ").trim()
            }

            if (cleanLabel.isNotBlank()) {
                val targetPkg = getPackageForExactUniqueLabel(cleanLabel)
                if (targetPkg != null) {
                    FreezerManager.registerAppOpen(this, targetPkg)
                    if (combined.contains("Disabled ", ignoreCase = true) ||
                        combined.contains("Paused ", ignoreCase = true) ||
                        FreezerManager.isSpecialFreeze(this, targetPkg)) {
                        FreezerManager.launchApp(this, targetPkg)
                    }
                    return // App launch triggered & protected! Do NOT fall through to recents check!
                }
            }
        }

        // 2. Targeted Recents Task Dismissal / Task Clear Detection
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val isExplicitRecents = clsName.contains("Recents", ignoreCase = true) ||
                                    clsName.contains("Overview", ignoreCase = true) ||
                                    clsName.contains("TaskView", ignoreCase = true) ||
                                    clsName.contains("ClearAll", ignoreCase = true) ||
                                    clsName.contains("Dismiss", ignoreCase = true) ||
                                    event.contentDescription?.contains("Clear all", ignoreCase = true) == true ||
                                    event.text?.any { it.contains("Clear all", ignoreCase = true) } == true
            if (isExplicitRecents) {
                dispatchRecentsCheck()
            }
            return
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        if (pkgName.isBlank()) return

        // Ignore system overlays, keyboards, volume sliders, and transient dialogs
        if (ignoredSystemPackages.contains(pkgName)) {
            val isRecentsProvider = clsName.contains("Recents", ignoreCase = true) ||
                                    clsName.contains("Overview", ignoreCase = true) ||
                                    clsName.contains("Quickstep", ignoreCase = true)
            if (isRecentsProvider) {
                dispatchRecentsCheck()
            }
            return
        }

        val isCallOrCameraActivity = clsName.contains("Voip", ignoreCase = true) ||
                                     clsName.contains("Call", ignoreCase = true) ||
                                     clsName.contains("Camera", ignoreCase = true) ||
                                     clsName.contains("Video", ignoreCase = true)

        // Instant session registration: Protect app from freeze & unfreeze immediately
        val isHomeOrRecents = isHomeOrLauncher(pkgName, clsName)
        if (!isHomeOrRecents && pkgName != packageName) {
            FreezerManager.registerAppOpen(this, pkgName)
        }

        // When returning to launcher / home screen, trigger instant recents check
        if (isHomeOrRecents) {
            dispatchRecentsCheck()
        }

        val now = android.os.SystemClock.uptimeMillis()
        if (pkgName == lastDispatchedPkg && !isCallOrCameraActivity) return
        if (pkgName == lastDispatchedPkg && (now - lastForegroundDispatchTime < 50)) return

        lastDispatchedPkg = pkgName
        lastForegroundDispatchTime = now

        // Instant notification to AutoTweakService with zero polling delay
        val intent = Intent(this, AutoTweakService::class.java).apply {
            action = AutoTweakService.ACTION_FOREGROUND_APP_CHANGED
            putExtra(AutoTweakService.EXTRA_PACKAGE_NAME, pkgName)
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e("AppEventService", "Error dispatching window event: ${e.message}")
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    companion object {
        private var lastLabelCacheTime = 0L
        private val appLabelToPackageMap = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun invalidateLabelCache() {
            appLabelToPackageMap.clear()
            lastLabelCacheTime = 0L
        }

        /**
         * Automatically enables this Accessibility Service via Root (Zero User Interaction).
         */
        fun enableViaRoot(packageName: String) {
            kotlin.concurrent.thread {
                val serviceComponent = "$packageName/${AppEventService::class.java.canonicalName}"
                val res = ShellUtils.runAsRoot("settings get secure enabled_accessibility_services", 2500)
                if (!res.isSuccess) return@thread
                val currentServices = res.output.trim()
                if (currentServices.contains("Timed Out") || currentServices.contains("Shell Busy") || currentServices.startsWith("Error")) {
                    return@thread
                }
                
                if (!currentServices.contains(serviceComponent)) {
                    val updated = if (currentServices.isEmpty() || currentServices == "null") {
                        serviceComponent
                    } else {
                        "$currentServices:$serviceComponent"
                    }
                    ShellUtils.fastCmd("settings put secure enabled_accessibility_services '$updated'")
                    ShellUtils.fastCmd("settings put secure accessibility_enabled 1")
                }
            }
        }
    }
}
