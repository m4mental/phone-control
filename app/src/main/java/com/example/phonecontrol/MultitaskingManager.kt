package com.example.phonecontrol

import android.content.Context
import kotlin.concurrent.thread

object MultitaskingManager {
    /**
     * Default core apps that must NEVER be killed or restricted during sleep/standby.
     */
    val protectedApps = listOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.instagram.android",
        "com.facebook.katana",
        "com.snapchat.android",
        "com.android.chrome",
        "com.brave.browser",
        "org.mozilla.firefox",
        "com.google.android.youtube",
        "io.github.sds100.keymapper",
        "io.github.sds100.keymapper.debug",
        "com.keymapper"
    )

    private const val PREF_KEY_UNIVERSAL_WHITELIST = "universal_protected_whitelist"

    fun isProtected(packageName: String, context: Context? = null): Boolean {
        if (protectedApps.contains(packageName)) return true
        if (context != null) {
            val userWhitelist = getUserWhitelist(context)
            if (userWhitelist.contains(packageName)) return true
        }
        return false
    }

    /**
     * Single Unified Whitelist across Force Doze, Super Doze, Standby Guard, and Hibernation.
     */
    fun getUserWhitelist(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val universalSet = prefs.getStringSet(PREF_KEY_UNIVERSAL_WHITELIST, null)
        if (universalSet != null) {
            return universalSet
        }
        // Migration from legacy keys if present
        val dozeSet = prefs.getStringSet("doze_whitelist", emptySet()) ?: emptySet()
        val multiPrefs = context.getSharedPreferences("multitasking_prefs", Context.MODE_PRIVATE)
        val multiSet = multiPrefs.getStringSet("user_whitelist", emptySet()) ?: emptySet()
        val combined = dozeSet + multiSet
        if (combined.isNotEmpty()) {
            prefs.edit().putStringSet(PREF_KEY_UNIVERSAL_WHITELIST, combined).apply()
        }
        return combined
    }

    fun addAppToWhitelist(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val current = getUserWhitelist(context).toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(PREF_KEY_UNIVERSAL_WHITELIST, current).apply()

        // Also sync legacy keys for backwards compatibility
        prefs.edit().putStringSet("doze_whitelist", current).apply()
        context.getSharedPreferences("multitasking_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet("user_whitelist", current).apply()

        // Grant comprehensive kernel, doze, appops & standby exemptions immediately
        grantFullExemption(packageName)
    }

    fun removeAppFromWhitelist(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val current = getUserWhitelist(context).toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(PREF_KEY_UNIVERSAL_WHITELIST, current).apply()

        // Also sync legacy keys
        prefs.edit().putStringSet("doze_whitelist", current).apply()
        context.getSharedPreferences("multitasking_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet("user_whitelist", current).apply()

        thread {
            ShellUtils.runAsRoot("dumpsys deviceidle whitelist -$packageName 2>/dev/null")
            ShellUtils.runAsRoot("dumpsys deviceidle except-idle-whitelist -$packageName 2>/dev/null")
        }
    }

    /**
     * Applies full 7-point kernel and system exemption for an app.
     */
    fun grantFullExemption(packageName: String) {
        thread {
            ShellUtils.runAsRoot("dumpsys deviceidle whitelist +$packageName 2>/dev/null")
            ShellUtils.runAsRoot("dumpsys deviceidle except-idle-whitelist +$packageName 2>/dev/null")
            ShellUtils.runAsRoot("cmd appops set $packageName RUN_IN_BACKGROUND allow 2>/dev/null")
            ShellUtils.runAsRoot("cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow 2>/dev/null")
            ShellUtils.runAsRoot("cmd appops set $packageName WAKE_LOCK allow 2>/dev/null")
            ShellUtils.runAsRoot("cmd appops set $packageName SYSTEM_ALERT_WINDOW allow 2>/dev/null")
            ShellUtils.runAsRoot("am set-standby-bucket $packageName active 2>/dev/null")
            ShellUtils.runAsRoot("cmd activity set-inactive $packageName false 2>/dev/null")
        }
    }
}
