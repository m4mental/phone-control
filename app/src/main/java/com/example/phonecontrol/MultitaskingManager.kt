package com.example.phonecontrol

import android.content.Context
import kotlin.concurrent.thread

object MultitaskingManager {
    /**
     * Default core apps that must NEVER be killed or restricted during sleep/standby.
     */
    val protectedApps = listOf(
        "com.example.phonecontrol",
        "com.google.android.gms"
    )

    private const val PREF_KEY_UNIVERSAL_WHITELIST = "universal_protected_whitelist"

    fun isProtected(packageName: String, context: Context? = null): Boolean {
        val (pkg, userId) = FreezerManager.parsePackageKey(packageName)
        if (protectedApps.contains(packageName) || protectedApps.contains(pkg)) return true
        if (context != null) {
            val userWhitelist = getUserWhitelist(context)
            if (userWhitelist.contains(packageName) || userWhitelist.contains(pkg)) return true
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

        // Grant comprehensive kernel, doze, appops, netpolicy & standby exemptions immediately
        grantFullExemption(packageName, context)
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
            val (pkg, userId) = FreezerManager.parsePackageKey(packageName)
            ShellUtils.fastCmd("dumpsys deviceidle whitelist -$pkg 2>/dev/null; dumpsys deviceidle except-idle-whitelist -$pkg 2>/dev/null")
        }
    }

    /**
     * Applies full 9-point kernel, doze, app-idle, netpolicy, appops and standby exemption for an app.
     * Batched via fastBatchCmd to eliminate timeouts and prevent shell locks.
     */
    fun grantFullExemption(packageName: String, context: Context? = null) {
        thread {
            val (pkg, userId) = FreezerManager.parsePackageKey(packageName)
            val uid = try {
                if (userId != 0 && context != null) {
                    ProfileAppUtils.getInstalledAppsAcrossProfiles(context).find { it.packageName == pkg && it.userId == userId }?.uid
                } else {
                    context?.packageManager?.getApplicationInfo(pkg, 0)?.uid
                }
            } catch (e: Exception) { null }

            val batchCommands = mutableListOf(
                "dumpsys deviceidle whitelist +$pkg 2>/dev/null",
                "cmd deviceidle whitelist +$pkg 2>/dev/null",
                "dumpsys deviceidle except-idle-whitelist +$pkg 2>/dev/null",
                "cmd appops set --user $userId $pkg RUN_IN_BACKGROUND allow 2>/dev/null || cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null",
                "cmd appops set --user $userId $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null || cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null",
                "cmd appops set --user $userId $pkg WAKE_LOCK allow 2>/dev/null || cmd appops set $pkg WAKE_LOCK allow 2>/dev/null",
                "cmd appops set --user $userId $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null || cmd appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null",
                "am set-standby-bucket --user $userId $pkg active 2>/dev/null || am set-standby-bucket $pkg active 2>/dev/null",
                "cmd activity set-inactive --user $userId $pkg false 2>/dev/null || cmd activity set-inactive $pkg false 2>/dev/null"
            )
            if (uid != null && uid > 0) {
                batchCommands.add("cmd netpolicy add restrict-background-whitelist $uid 2>/dev/null")
                batchCommands.add("cmd netpolicy add app-idle-whitelist $uid 2>/dev/null")
            } else {
                batchCommands.add("pkg_uid=\$(pm list packages --user $userId -U 2>/dev/null | grep \"package:$pkg \" | sed -n 's/.*uid://p'); if [ -n \"\$pkg_uid\" ]; then cmd netpolicy add restrict-background-whitelist \$pkg_uid 2>/dev/null; cmd netpolicy add app-idle-whitelist \$pkg_uid 2>/dev/null; fi")
            }
            ShellUtils.fastBatchCmd(batchCommands)
        }
    }
}
