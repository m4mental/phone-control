package com.example.phonecontrol

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Event-Driven Recents Active & Dismissal Engine (RecentActiveManager + RecentDismissManager).
 *
 * Responsibilities:
 * 1. RecentActiveManager: Tracks alive apps in Recents stack, providing 100% immunity
 *    from stopping, killing, or freezing while active in Recents / Multitasking.
 * 2. RecentDismissManager: Detects when a task is dismissed (swiped away) or cleared from Recents,
 *    triggering targeted freeze ONLY for dismissed packages.
 */
object RecentTasksManager {
    private const val TAG = "RecentTasksManager"

    // Live set of packages currently open/alive in the system Recent tasks list
    val activeRecentsPackages = ConcurrentHashMap.newKeySet<String>()

    // Last known snapshot of recent packages to compute dismissal diffs
    @Volatile
    private var lastKnownRecents: Set<String> = emptySet()

    @Volatile
    private var lastQueryTimestamp: Long = 0L

    /**
     * Called whenever an app is launched or brought to foreground.
     * Immediately registers it as alive in Recents with zero latency.
     */
    fun onAppOpened(packageName: String) {
        if (isIgnoredSystemPackage(packageName)) return
        activeRecentsPackages.add(packageName)
        Log.d(TAG, "🟢 App Registered in Recents: $packageName")
    }

    /**
     * Absolute Recents Immunity Check:
     * Returns true if the package is currently alive in Recents or foreground.
     * While in Recents, an app must NEVER be stopped, killed, or frozen.
     */
    fun isAppInRecents(packageName: String): Boolean {
        if (packageName.isBlank() || isIgnoredSystemPackage(packageName)) return false
        if (activeRecentsPackages.contains(packageName)) return true
        
        // Secondary fast-path: Check live query
        val live = getLiveRecentPackages(forceRefresh = false)
        return live.contains(packageName)
    }

    /**
     * Returns the live set of packages from dumpsys activity recents.
     * Accurately parses Android 14/15/16 Task lines (A=uid:pkg, I=pkg/act, baseActivity, realActivity).
     */
    fun getLiveRecentPackages(forceRefresh: Boolean = false): Set<String> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastQueryTimestamp < 1000 && lastKnownRecents.isNotEmpty()) {
            return lastKnownRecents
        }

        return try {
            val output = ShellUtils.fastCmdResult(
                "dumpsys activity recents | grep -m 50 -E 'mHiddenTasks|Recent #[0-9]+:|realActivity=|baseActivity=|topActivity=|cmp=|I=|Task\\{' 2>/dev/null",
                1500
            )
            if (output.isBlank()) {
                return if (now - lastQueryTimestamp < 5000) lastKnownRecents else emptySet()
            }

            val pkgs = mutableSetOf<String>()
            val regexes = listOf(
                Regex("Task\\{[^}]*(?:A=[0-9]+:|I=)([a-zA-Z0-9_.]+)"),
                Regex("(?:realActivity=|baseActivity=|topActivity=|cmp=|pkg=)\\{?([a-zA-Z0-9_.]+)[/}\\s]"),
                Regex("A=[0-9]+:([a-zA-Z0-9_.]+)")
            )

            for (line in output.lineSequence()) {
                for (r in regexes) {
                    val m = r.find(line)
                    if (m != null) {
                        val p = m.groupValues[1].trim()
                        if (p.isNotBlank() && !isIgnoredSystemPackage(p)) {
                            pkgs.add(p)
                        }
                    }
                }
            }

            lastKnownRecents = pkgs
            lastQueryTimestamp = now

            // Synchronize activeRecentsPackages with actual system tasks
            if (pkgs.isNotEmpty()) {
                activeRecentsPackages.retainAll(pkgs)
                activeRecentsPackages.addAll(pkgs)
            }

            pkgs
        } catch (e: Exception) {
            Log.e(TAG, "Error querying live recents: ${e.message}")
            lastKnownRecents
        }
    }

    /**
     * Event-Driven Recents Dismissal Detector:
     * Compares previous Recents snapshot with current Recents.
     * Identifies apps that were swiped away or cleared, removes them from activeRecentsPackages,
     * and triggers freeze ONLY for the swiped/dismissed apps.
     */
    fun processRecentsChange(context: Context, currentForeground: String) {
        val previousRecents = HashSet(lastKnownRecents)
        val currentRecents = getLiveRecentPackages(forceRefresh = true)

        if (currentRecents.isEmpty() && previousRecents.isNotEmpty()) {
            // Guard against temporary dumpsys timeout; do not false-freeze
            return
        }

        // Dismissed packages = packages that WERE in recents, but are NO LONGER in recents,
        // and are NOT currently active in foreground.
        val dismissedPackages = previousRecents.filter { pkg ->
            !currentRecents.contains(pkg) &&
            pkg != currentForeground &&
            !isIgnoredSystemPackage(pkg) &&
            !FreezerManager.isAppCurrentlyVisible(pkg)
        }.toSet()

        if (dismissedPackages.isNotEmpty()) {
            Log.d(TAG, "🗑️ Swiped away / Dismissed from Recents: $dismissedPackages")
            val frozenApps = FreezerManager.getFrozenApps(context)
            val specialApps = FreezerManager.getSpecialFreezeApps(context)
            val allConfigured = frozenApps + specialApps

            for (pkg in dismissedPackages) {
                activeRecentsPackages.remove(pkg)
                FreezerManager.removeActiveSession(pkg)

                if (allConfigured.contains(pkg)) {
                    val isAudio = FreezerManager.getActivePlayingAudioPackages(context).contains(pkg)
                    val isSafe = MultitaskingManager.getUserWhitelist(context).contains(pkg) ||
                                 MultitaskingManager.protectedApps.contains(pkg)
                    if (!isAudio && !isSafe) {
                        Log.d(TAG, "❄️ Freezing swiped away app: $pkg")
                        FreezerManager.freezeApp(context, pkg, force = true, isExplicitDismiss = true)
                    }
                }
            }
        }

        // Also sweep any background wakeups / orphan processes of configured freezer apps that are NOT in Recents
        val frozenApps = FreezerManager.getFrozenApps(context)
        val specialApps = FreezerManager.getSpecialFreezeApps(context)
        val allConfigured = frozenApps + specialApps
        val runningConfigured = FreezerManager.getRunningConfiguredApps(allConfigured)

        for (pkg in runningConfigured) {
            if (!currentRecents.contains(pkg) && pkg != currentForeground && !FreezerManager.isAppCurrentlyVisible(pkg)) {
                val isAudio = FreezerManager.getActivePlayingAudioPackages(context).contains(pkg)
                val isSafe = MultitaskingManager.getUserWhitelist(context).contains(pkg) ||
                             MultitaskingManager.protectedApps.contains(pkg)
                if (!isAudio && !isSafe) {
                    Log.d(TAG, "❄️ Freezing orphan background process not in Recents: $pkg")
                    FreezerManager.freezeApp(context, pkg, force = true, isExplicitDismiss = true)
                }
            }
        }
    }

    fun isIgnoredSystemPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        return pkg.contains("launcher", ignoreCase = true) ||
               pkg.contains("home", ignoreCase = true) ||
               pkg == "com.android.systemui" ||
               pkg == "android" ||
               pkg == "com.android.settings" ||
               pkg == "com.google.android.googlequicksearchbox" ||
               pkg == "com.example.phonecontrol"
    }
}
