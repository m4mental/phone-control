package com.example.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log

object FirewallManager {

    private const val TAG = "FirewallManager"
    const val PREFS_NAME = "firewall_prefs"
    const val KEY_FIREWALL_PAUSED = "firewall_paused"
    const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    const val MAX_SLOTS = 5
    const val SLOT_KEY_PREFIX = "tile_slot_"

    fun isFirewallPaused(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FIREWALL_PAUSED, false)
    }

    fun setFirewallPaused(context: Context, paused: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FIREWALL_PAUSED, paused).apply()

        val blockedKeys = getBlockedPackages(context)
        kotlin.concurrent.thread {
            try {
                for (key in blockedKeys) {
                    val (pkg, userId) = if (key.contains(":")) {
                        val parts = key.split(":")
                        Pair(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 0)
                    } else {
                        Pair(key, 0)
                    }

                    val storedUid = prefs.getInt("uid_$key", if (userId == 0) prefs.getInt("uid_$pkg", -1) else -1)
                    if (storedUid == -1) continue

                    if (paused) {
                        // Unblock all in iptables (paused/restored state)
                        TweakManager.setFirewallRule(storedUid, blockWifi = false, blockData = false)
                    } else {
                        // Re-apply saved blocking rules
                        val curWifi = prefs.getBoolean("block_wifi_$key", true)
                        val curData = prefs.getBoolean("block_data_$key", true)
                        TweakManager.setFirewallRule(storedUid, curWifi, curData)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying setFirewallPaused rules: ${e.message}", e)
            } finally {
                updateAllTiles(context)
            }
        }
    }

    fun getBlockedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun isAppBlocked(context: Context, appKey: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isPaused = isFirewallPaused(context)
        if (isPaused) return false
        val curWifi = prefs.getBoolean("block_wifi_$appKey", true)
        val curData = prefs.getBoolean("block_data_$appKey", true)
        return curWifi || curData
    }

    fun toggleAppBlocked(context: Context, appKey: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val (pkg, userId) = if (appKey.contains(":")) {
            val parts = appKey.split(":")
            Pair(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 0)
        } else {
            Pair(appKey, 0)
        }

        val curWifi = prefs.getBoolean("block_wifi_$appKey", true)
        val curData = prefs.getBoolean("block_data_$appKey", true)
        val isPaused = isFirewallPaused(context)
        val currentlyBlocked = !isPaused && (curWifi || curData)

        val newBlockedState = !currentlyBlocked
        val editor = prefs.edit()
            .putBoolean("block_wifi_$appKey", newBlockedState)
            .putBoolean("block_data_$appKey", newBlockedState)

        if (newBlockedState && isPaused) {
            editor.putBoolean(KEY_FIREWALL_PAUSED, false)
        }

        if (userId == 0) {
            editor.putBoolean("block_wifi_$pkg", newBlockedState)
            editor.putBoolean("block_data_$pkg", newBlockedState)
        }
        editor.apply()

        val storedUid = prefs.getInt("uid_$appKey", if (userId == 0) prefs.getInt("uid_$pkg", -1) else -1)
        if (storedUid != -1) {
            kotlin.concurrent.thread {
                try {
                    TweakManager.setFirewallRule(storedUid, blockWifi = newBlockedState, blockData = newBlockedState)
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling firewall rule for $appKey: ${e.message}", e)
                }
            }
        }

        return newBlockedState
    }

    fun getAssignedSlot(context: Context, appKey: String): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (i in 1..MAX_SLOTS) {
            if (prefs.getString("${SLOT_KEY_PREFIX}${i}_key", null) == appKey) {
                return i
            }
        }
        return null
    }

    fun getAppForSlot(context: Context, slot: Int): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("${SLOT_KEY_PREFIX}${slot}_key", null)
    }

    fun assignAppToSlot(context: Context, appKey: String): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // If already assigned to a slot, return it
        val existingSlot = getAssignedSlot(context, appKey)
        if (existingSlot != null) {
            return existingSlot
        }

        // Find first empty slot
        for (i in 1..MAX_SLOTS) {
            val assigned = prefs.getString("${SLOT_KEY_PREFIX}${i}_key", null)
            if (assigned == null) {
                prefs.edit().putString("${SLOT_KEY_PREFIX}${i}_key", appKey).apply()
                val serviceClass = getTileServiceClassForSlot(i)
                if (serviceClass != null) {
                    addTileToQuickSettings(context, serviceClass)
                    updateSlotTile(context, i)
                }
                return i
            }
        }
        return null
    }

    fun removeAppFromSlot(context: Context, appKey: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (i in 1..MAX_SLOTS) {
            if (prefs.getString("${SLOT_KEY_PREFIX}${i}_key", null) == appKey) {
                prefs.edit().remove("${SLOT_KEY_PREFIX}${i}_key").apply()
                val serviceClass = getTileServiceClassForSlot(i)
                if (serviceClass != null) {
                    removeTileFromQuickSettings(context, serviceClass)
                    updateSlotTile(context, i)
                }
                return true
            }
        }
        return false
    }

    fun pruneUninstalledPackage(context: Context, uninstalledPkg: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false

        for (i in 1..MAX_SLOTS) {
            val assignedKey = prefs.getString("${SLOT_KEY_PREFIX}${i}_key", null)
            if (assignedKey != null && assignedKey.substringBefore(":") == uninstalledPkg) {
                removeAppFromSlot(context, assignedKey)
                changed = true
            }
        }

        val blocked = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val toRemove = blocked.filter { it.substringBefore(":") == uninstalledPkg }
        if (toRemove.isNotEmpty()) {
            blocked.removeAll(toRemove.toSet())
            editor.putStringSet(KEY_BLOCKED_PACKAGES, blocked)
            changed = true
        }

        val uidsToUnblock = mutableListOf<Int>()
        for ((key, value) in prefs.all) {
            if (key.startsWith("uid_$uninstalledPkg") && value is Int && value != -1) {
                uidsToUnblock.add(value)
            }
            if (key.startsWith(uninstalledPkg) || key.startsWith("uid_$uninstalledPkg") || key.startsWith("block_wifi_$uninstalledPkg") || key.startsWith("block_data_$uninstalledPkg")) {
                editor.remove(key)
                changed = true
            }
        }

        if (uidsToUnblock.isNotEmpty()) {
            kotlin.concurrent.thread {
                for (uid in uidsToUnblock) {
                    try {
                        TweakManager.setFirewallRule(uid, blockWifi = false, blockData = false)
                        TweakManager.setAppBandwidthLimit(uid, 0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing iptables rule for uninstalled uid $uid: ${e.message}")
                    }
                }
            }
        }

        if (changed) {
            editor.apply()
            updateAllTiles(context)
        }
    }

    fun getAppBandwidthLimit(context: Context, appKey: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("bandwidth_limit_$appKey", 0)
    }

    fun setAppBandwidthLimit(context: Context, appKey: String, limitKbps: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("bandwidth_limit_$appKey", limitKbps).apply()
        val uid = prefs.getInt("uid_$appKey", -1)
        if (uid != -1) {
            kotlin.concurrent.thread {
                TweakManager.setAppBandwidthLimit(uid, limitKbps)
            }
        }
    }

    fun getTileServiceClassForSlot(slot: Int): Class<out TileService>? {
        return when (slot) {
            1 -> AppFirewallTile1Service::class.java
            2 -> AppFirewallTile2Service::class.java
            3 -> AppFirewallTile3Service::class.java
            4 -> AppFirewallTile4Service::class.java
            5 -> AppFirewallTile5Service::class.java
            else -> null
        }
    }

    fun addTileToQuickSettings(context: Context, serviceClass: Class<*>) {
        val comp = "custom(${context.packageName}/.${serviceClass.simpleName})"
        kotlin.concurrent.thread {
            try {
                val res = ShellUtils.runAsRoot("settings get secure sysui_qs_tiles", 2500)
                val current = res.output.trim()
                if (current.isNotEmpty() && current != "null" && res.exitCode == 0) {
                    val list = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    if (!list.contains(comp)) {
                        list.add(comp)
                        val newTiles = list.joinToString(",")
                        ShellUtils.fastCmd("settings put secure sysui_qs_tiles \"$newTiles\"")
                    }
                } else {
                    ShellUtils.fastCmd("settings put secure sysui_qs_tiles \"$comp\"")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting tile $comp into sysui_qs_tiles: ${e.message}")
            }
        }
    }

    fun removeTileFromQuickSettings(context: Context, serviceClass: Class<*>) {
        val comp = "custom(${context.packageName}/.${serviceClass.simpleName})"
        kotlin.concurrent.thread {
            try {
                val res = ShellUtils.runAsRoot("settings get secure sysui_qs_tiles", 2500)
                val current = res.output.trim()
                if (current.isNotEmpty() && current != "null" && res.exitCode == 0) {
                    val list = current.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != comp }
                    val newTiles = list.joinToString(",")
                    ShellUtils.fastCmd("settings put secure sysui_qs_tiles \"$newTiles\"")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing tile $comp from sysui_qs_tiles: ${e.message}")
            }
        }
    }

    fun updateMasterTile(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, FirewallMasterTileService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update MasterTile: ${e.message}")
            }
        }
    }

    fun updateSlotTile(context: Context, slot: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val serviceClass = getTileServiceClassForSlot(slot) ?: return
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, serviceClass)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update slot $slot tile: ${e.message}")
            }
        }
    }

    fun updateAllTiles(context: Context) {
        updateMasterTile(context)
        for (i in 1..MAX_SLOTS) {
            updateSlotTile(context, i)
        }
    }

    fun getAppLabelFormatted(context: Context, appKey: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawLabel = prefs.getString("label_$appKey", null) ?: appKey.substringBefore(":")
        val cat = prefs.getString("cat_$appKey", "") ?: ""
        val userId = if (appKey.contains(":")) {
            appKey.substringAfter(":").toIntOrNull() ?: 0
        } else {
            0
        }
        return when {
            cat == "DUAL" || userId == 999 -> "$rawLabel (Clone)"
            cat == "PRIVATE" || userId >= 10 -> "$rawLabel (Private)"
            else -> rawLabel
        }
    }

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, Icon>()

    fun getAppTileIcon(context: Context, appKey: String): Icon {
        iconCache[appKey]?.let { return it }

        val (pkg, userId) = if (appKey.contains(":")) {
            val parts = appKey.split(":")
            Pair(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 0)
        } else {
            Pair(appKey, 0)
        }

        val icon = try {
            val pm = context.packageManager
            var drawable: android.graphics.drawable.Drawable? = null

            if (userId != 0) {
                try {
                    val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
                    val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? android.content.pm.LauncherApps
                    val targetUser = userManager?.userProfiles?.find {
                        Regex("""\d+""").find(it.toString())?.value?.toIntOrNull() == userId
                    }
                    if (targetUser != null && launcherApps != null) {
                        val acts = launcherApps.getActivityList(pkg, targetUser)
                        if (acts.isNotEmpty()) {
                            drawable = acts[0].getBadgedIcon(0)
                        }
                    }
                } catch (_: Exception) {}
            }

            if (drawable == null) {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_DISABLED_COMPONENTS
                }
                val appInfo = pm.getApplicationInfo(pkg, flags)
                drawable = pm.getApplicationIcon(appInfo)
            }

            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            Icon.createWithBitmap(bitmap)
        } catch (_: Exception) {
            Icon.createWithResource(context, R.drawable.ic_sub_firewall)
        }

        iconCache[appKey] = icon
        return icon
    }
}
