package com.example.phonecontrol

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object DynamicPrivacyManager {
    private const val TAG = "DynamicPrivacy"
    const val PREFS_NAME = "dynamic_privacy_prefs"
    const val PREF_MASTER_ENABLED = "master_enabled"
    const val PREF_GUARD_LOCATION = "guard_location"
    const val PREF_GUARD_CAMERA = "guard_camera"
    const val PREF_GUARD_MIC = "guard_mic"
    const val PREF_GUARD_CLIPBOARD = "guard_clipboard"
    const val PREF_GUARDED_PACKAGES = "guarded_packages"

    private val privacyExecutor = Executors.newSingleThreadExecutor()
    private val currentlyLockedApps = ConcurrentHashMap.newKeySet<String>()

    val OP_LOCATION = listOf("FINE_LOCATION", "COARSE_LOCATION", "MONITOR_LOCATION", "MONITOR_HIGH_POWER_LOCATION")
    val OP_CAMERA = listOf("CAMERA")
    val OP_MIC = listOf("RECORD_AUDIO")
    val OP_CLIPBOARD = listOf("READ_CLIPBOARD")

    // Common sensitive apps: social, messaging, delivery, cab, travel, shopping
    val POPULAR_SENSITIVE_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thunderdog.challegram",
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.snapchat.android",
        "com.twitter.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.reddit.frontpage",
        "in.swiggy.android",
        "com.application.zomato",
        "com.ubercab",
        "com.olacabs.customer",
        "com.rapido.passenger",
        "cris.org.in.prs.ima",
        "com.makemytrip",
        "com.goibibo",
        "com.amazon.mShop.android.shopping",
        "com.flipkart.android",
        "com.myntra.android",
        "com.truecaller",
        "com.discord",
        "com.linkedin.android",
        "com.pinterest",
        "com.google.android.apps.tachyon"
    )

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isMasterEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_MASTER_ENABLED, false)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_MASTER_ENABLED, enabled).apply()
        // Synchronize main settings toggle
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dynamic_privacy_enabled", enabled).apply()

        privacyExecutor.execute {
            if (enabled) {
                lockAllGuardedApps(context)
            } else {
                unlockAllGuardedApps(context)
            }
        }
    }

    fun isGuardLocation(context: Context): Boolean = getPrefs(context).getBoolean(PREF_GUARD_LOCATION, true)
    fun setGuardLocation(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(PREF_GUARD_LOCATION, enabled).apply()

    fun isGuardCamera(context: Context): Boolean = getPrefs(context).getBoolean(PREF_GUARD_CAMERA, true)
    fun setGuardCamera(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(PREF_GUARD_CAMERA, enabled).apply()

    fun isGuardMic(context: Context): Boolean = getPrefs(context).getBoolean(PREF_GUARD_MIC, true)
    fun setGuardMic(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(PREF_GUARD_MIC, enabled).apply()

    fun isGuardClipboard(context: Context): Boolean = getPrefs(context).getBoolean(PREF_GUARD_CLIPBOARD, true)
    fun setGuardClipboard(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(PREF_GUARD_CLIPBOARD, enabled).apply()

    fun getGuardedPackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(PREF_GUARDED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun isAppGuarded(context: Context, pkg: String): Boolean {
        return getGuardedPackages(context).contains(pkg)
    }

    fun setAppGuarded(context: Context, pkg: String, guarded: Boolean) {
        val current = getGuardedPackages(context).toMutableSet()
        if (guarded) {
            current.add(pkg)
        } else {
            current.remove(pkg)
        }
        getPrefs(context).edit().putStringSet(PREF_GUARDED_PACKAGES, current).apply()
        if (!guarded) {
            privacyExecutor.execute { unlockApp(context, pkg) }
        } else if (isMasterEnabled(context)) {
            privacyExecutor.execute { lockApp(context, pkg) }
        }
    }

    fun setGuardedPackages(context: Context, packages: Set<String>) {
        val oldGuarded = getGuardedPackages(context)
        getPrefs(context).edit().putStringSet(PREF_GUARDED_PACKAGES, packages).apply()
        privacyExecutor.execute {
            val removed = oldGuarded - packages
            for (pkg in removed) {
                unlockApp(context, pkg)
            }
            if (isMasterEnabled(context)) {
                val added = packages - oldGuarded
                for (pkg in added) {
                    lockApp(context, pkg)
                }
            }
        }
    }

    fun getActiveOps(context: Context): List<String> {
        val ops = mutableListOf<String>()
        if (isGuardLocation(context)) ops.addAll(OP_LOCATION)
        if (isGuardCamera(context)) ops.addAll(OP_CAMERA)
        if (isGuardMic(context)) ops.addAll(OP_MIC)
        if (isGuardClipboard(context)) ops.addAll(OP_CLIPBOARD)
        return ops
    }

    fun lockApp(context: Context, pkg: String) {
        if (!ShellUtils.isValidPackageName(pkg) || pkg == context.packageName) return
        val ops = getActiveOps(context)
        if (ops.isEmpty()) return

        val sb = StringBuilder()
        for (op in ops) {
            sb.append("cmd appops set $pkg $op ignore; cmd appops set --uid $pkg $op ignore; ")
        }
        ShellUtils.fastCmd(sb.toString())
        currentlyLockedApps.add(pkg)
        Log.d(TAG, "🔒 Locked privacy ops for $pkg [${ops.size} ops]")
    }

    fun unlockApp(context: Context, pkg: String) {
        if (!ShellUtils.isValidPackageName(pkg)) return
        val ops = getActiveOps(context)
        if (ops.isEmpty()) return

        val sb = StringBuilder()
        for (op in ops) {
            sb.append("cmd appops set $pkg $op allow; cmd appops set --uid $pkg $op allow; ")
        }
        ShellUtils.fastCmd(sb.toString())
        currentlyLockedApps.remove(pkg)
        Log.d(TAG, "🔓 Unlocked privacy ops for $pkg [${ops.size} ops]")
    }

    fun lockAllGuardedApps(context: Context) {
        if (!isMasterEnabled(context)) return
        val guarded = getGuardedPackages(context)
        if (guarded.isEmpty()) return
        val ops = getActiveOps(context)
        if (ops.isEmpty()) return

        val sb = StringBuilder()
        for (pkg in guarded) {
            if (pkg == context.packageName || !ShellUtils.isValidPackageName(pkg)) continue
            for (op in ops) {
                sb.append("cmd appops set $pkg $op ignore; cmd appops set --uid $pkg $op ignore; ")
            }
            currentlyLockedApps.add(pkg)
        }
        ShellUtils.fastCmd(sb.toString())
        Log.d(TAG, "🔒 Locked all guarded apps (${guarded.size} apps)")
    }

    fun unlockAllGuardedApps(context: Context) {
        val guarded = getGuardedPackages(context) + currentlyLockedApps
        if (guarded.isEmpty()) return
        val ops = OP_LOCATION + OP_CAMERA + OP_MIC + OP_CLIPBOARD

        val sb = StringBuilder()
        for (pkg in guarded) {
            if (!ShellUtils.isValidPackageName(pkg)) continue
            for (op in ops) {
                sb.append("cmd appops set $pkg $op allow; cmd appops set --uid $pkg $op allow; ")
            }
        }
        ShellUtils.fastCmd(sb.toString())
        currentlyLockedApps.clear()
        Log.d(TAG, "🔓 Restored all guarded apps to allow")
    }

    fun onForegroundAppTransition(context: Context, previousPkg: String, newPkg: String) {
        if (!isMasterEnabled(context)) return
        val guarded = getGuardedPackages(context)

        // If previous app left foreground and is guarded -> lock immediately
        if (previousPkg.isNotBlank() && previousPkg != newPkg && guarded.contains(previousPkg)) {
            lockApp(context, previousPkg)
        }

        // If new foreground app is guarded -> unlock immediately for seamless user experience
        if (newPkg.isNotBlank() && guarded.contains(newPkg)) {
            unlockApp(context, newPkg)
        }
    }

    fun onScreenOff(context: Context) {
        if (!isMasterEnabled(context)) return
        lockAllGuardedApps(context)
    }

    fun onScreenOn(context: Context, currentForegroundPkg: String) {
        if (!isMasterEnabled(context)) return
        if (currentForegroundPkg.isNotBlank() && isAppGuarded(context, currentForegroundPkg)) {
            unlockApp(context, currentForegroundPkg)
        }
    }

    fun autoSelectSensitiveApps(context: Context): Set<String> {
        val pm = context.packageManager
        val installed = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            emptyList()
        }

        val result = mutableSetOf<String>()
        for (pkgInfo in installed) {
            val pkg = pkgInfo.packageName
            val isSystem = (pkgInfo.applicationInfo != null && (pkgInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            if (pkg == context.packageName) continue

            // 1. Check known popular sensitive apps
            if (POPULAR_SENSITIVE_PACKAGES.contains(pkg)) {
                result.add(pkg)
                continue
            }

            // 2. Non-system apps requesting sensitive dangerous permissions (Camera, Mic, GPS)
            val perms = pkgInfo.requestedPermissions
            if (!isSystem && perms != null) {
                val hasLocation = perms.contains(android.Manifest.permission.ACCESS_FINE_LOCATION) || perms.contains(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                val hasCamera = perms.contains(android.Manifest.permission.CAMERA)
                val hasMic = perms.contains(android.Manifest.permission.RECORD_AUDIO)
                if (hasLocation || hasCamera || hasMic) {
                    result.add(pkg)
                }
            }
        }

        setGuardedPackages(context, result)
        return result
    }
}
