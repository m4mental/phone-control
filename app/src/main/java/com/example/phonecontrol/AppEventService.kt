package com.example.phonecontrol

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Ultra-Fast 0ms Event-Driven App & Window State Listener.
 * Replaces the old 3000ms dumpsys polling loop with direct OS callbacks.
 */
class AppEventService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkgName = event.packageName?.toString() ?: return
        if (pkgName.isBlank()) return

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
