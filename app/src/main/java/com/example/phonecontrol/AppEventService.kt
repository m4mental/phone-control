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
    private var lastLabelCacheTime = 0L
    private val appLabelToPackageMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun getPackageForLabel(label: String): String? {
        val now = System.currentTimeMillis()
        if (now - lastLabelCacheTime > 60000 || appLabelToPackageMap.isEmpty()) {
            appLabelToPackageMap.clear()
            val allFrozen = FreezerManager.getSpecialFreezeApps(this) + FreezerManager.getFrozenApps(this)
            for (pkg in allFrozen) {
                try {
                    val appInfo = packageManager.getApplicationInfo(pkg, 0)
                    val l = packageManager.getApplicationLabel(appInfo).toString().trim().lowercase()
                    appLabelToPackageMap[l] = pkg
                } catch (_: Exception) {}
            }
            lastLabelCacheTime = now
        }
        val clean = label.lowercase().trim()
        val direct = appLabelToPackageMap[clean]
        if (direct != null) return direct

        return appLabelToPackageMap.entries.firstOrNull { (k, _) -> clean.startsWith(k) || clean.contains(k) }?.value
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val pkgName = event.packageName?.toString() ?: ""
        val clsName = event.className?.toString() ?: ""

        // 1. Instant Launcher Click Detection: Pre-register & protect app on click before window transition starts
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val desc = event.contentDescription?.toString() ?: ""
            val text = event.text?.joinToString(" ") ?: ""
            val combined = if (desc.isNotBlank()) desc else text
            val cleanLabel = if (combined.contains("Disabled ", ignoreCase = true)) {
                combined.substringAfter("Disabled ").trim()
            } else {
                combined.trim()
            }

            if (cleanLabel.isNotBlank()) {
                val targetPkg = getPackageForLabel(cleanLabel)
                if (targetPkg != null) {
                    FreezerManager.registerAppOpen(targetPkg)
                    if (combined.contains("Disabled ", ignoreCase = true)) {
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
        
        // Auto-launch when user taps a legacy suspended app without pressing BACK
        if (clsName.contains("SuspendedAppActivity", ignoreCase = true)) {
            val specialApps = FreezerManager.getSpecialFreezeApps(this)
            val lastPkg = FreezerManager.lastLaunchedPackage
            val targetPkg = if (lastPkg != null && specialApps.contains(lastPkg)) {
                lastPkg
            } else {
                val text = event.text?.joinToString(" ") ?: ""
                val desc = event.contentDescription?.toString() ?: ""
                val combined = "$text $desc"
                specialApps.firstOrNull { pkg ->
                    try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        val label = packageManager.getApplicationLabel(appInfo).toString()
                        combined.contains(label, ignoreCase = true)
                    } catch (e: Exception) { false }
                }
            }
            if (targetPkg != null) {
                FreezerManager.unfreezeApp(targetPkg)
                FreezerManager.launchApp(this, targetPkg)
            }
            return
        }

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
        val isHomeOrRecents = pkgName.contains("launcher", ignoreCase = true) || clsName.contains("Recents", ignoreCase = true)
        if (!isHomeOrRecents && pkgName != packageName) {
            FreezerManager.registerAppOpen(pkgName)
        }

        // When returning to launcher / home screen, trigger instant recents check
        if (isHomeOrRecents) {
            dispatchRecentsCheck()
        }

        if (pkgName == lastDispatchedPkg && !isCallOrCameraActivity) return

        lastDispatchedPkg = pkgName

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
        /**
         * Automatically enables this Accessibility Service via Root (Zero User Interaction).
         */
        fun enableViaRoot(packageName: String) {
            kotlin.concurrent.thread {
                val serviceComponent = "$packageName/${AppEventService::class.java.canonicalName}"
                val currentServices = ShellUtils.runAsRoot("settings get secure enabled_accessibility_services").output.trim()
                
                if (!currentServices.contains(serviceComponent)) {
                    val updated = if (currentServices.isEmpty() || currentServices == "null") {
                        serviceComponent
                    } else {
                        "$currentServices:$serviceComponent"
                    }
                    ShellUtils.fastCmd("settings put secure enabled_accessibility_services $updated")
                    ShellUtils.fastCmd("settings put secure accessibility_enabled 1")
                }
            }
        }
    }
}
