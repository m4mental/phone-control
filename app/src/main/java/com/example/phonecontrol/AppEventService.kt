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

        // 1. Instant Recents Task Dismissal / Task Clear Detection
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val isRecentsProvider = pkgName == "com.android.systemui" ||
                                    pkgName.contains("launcher", ignoreCase = true) ||
                                    clsName.contains("Recents", ignoreCase = true) ||
                                    clsName.contains("Overview", ignoreCase = true)
            if (isRecentsProvider) {
                dispatchRecentsCheck()
            }
            return
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        if (pkgName.isBlank()) return
        
        // Ignore system overlays, keyboards, volume sliders, and transient dialogs
        if (ignoredSystemPackages.contains(pkgName)) {
            val isRecentsProvider = pkgName == "com.android.systemui" ||
                                    clsName.contains("Recents", ignoreCase = true) ||
                                    clsName.contains("Overview", ignoreCase = true)
            if (isRecentsProvider) {
                dispatchRecentsCheck()
            }
            return
        }

        val isCallOrCameraActivity = clsName.contains("Voip", ignoreCase = true) ||
                                     clsName.contains("Call", ignoreCase = true) ||
                                     clsName.contains("Camera", ignoreCase = true) ||
                                     clsName.contains("Video", ignoreCase = true)

        // When returning to launcher / home screen, trigger instant recents check
        if (pkgName.contains("launcher", ignoreCase = true) || clsName.contains("Recents", ignoreCase = true)) {
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
