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

    // Cache for hasActiveForegroundTask to eliminate redundant shell queries within short windows
    private val activeForegroundTaskCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /**
     * Smart Active Download & Foreground Service Guard:
     * Checks if the package is actively running a Foreground Service (FGS),
     * background download/upload (e.g. SpeedDown, 1DM), active sync, or holding a CPU WakeLock.
     *
     * In Android/Linux kernel:
     * - Processes with active Foreground Services have oom_score_adj <= 250
     *   (PERCEPTIBLE_APP_ADJ = 200, PERCEPTIBLE_LOW_APP_ADJ = 250).
     * - Idle/cached background processes have oom_score_adj >= 800 (900-999).
     *
     * Returns true if the app is actively performing foreground/download work,
     * guaranteeing dynamic immunity from freeze/kill even if not in Recents.
     */
    fun hasActiveForegroundTask(packageName: String, maxCacheAgeMs: Long = 800L): Boolean {
        if (packageName.isBlank() || isIgnoredSystemPackage(packageName)) return false

        val now = System.currentTimeMillis()
        val cached = activeForegroundTaskCache[packageName]
        if (cached != null && (now - cached.first < maxCacheAgeMs)) {
            return cached.second
        }

        return try {
            val script = """
                active=0
                pids=${'$'}(pgrep -f "^$packageName" 2>/dev/null || pidof "$packageName" 2>/dev/null)
                for p in ${'$'}pids; do
                    adj=${'$'}(cat /proc/${'$'}p/oom_score_adj 2>/dev/null)
                    if [ -n "${'$'}adj" ] && [ "${'$'}adj" -le 250 ]; then
                        active=1
                        break
                    fi
                done
                if [ "${'$'}active" -eq 0 ] && [ -n "${'$'}pids" ]; then
                    wl=${'$'}(dumpsys power 2>/dev/null | grep -E "PARTIAL_WAKE_LOCK.*$packageName" | head -n 1)
                    if [ -n "${'$'}wl" ]; then
                        active=1
                    fi
                fi
                echo "${'$'}active"
            """.trimIndent()

            val result = ShellUtils.fastCmdResult(script, 1500).trim()
            val isActive = (result == "1")
            activeForegroundTaskCache[packageName] = Pair(now, isActive)
            isActive
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active foreground task for $packageName: ${e.message}")
            false
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
        // and are NOT currently active in foreground, visible, or recently launched.
        val dismissedPackages = previousRecents.filter { pkg ->
            !currentRecents.contains(pkg) &&
            pkg != currentForeground &&
            !isIgnoredSystemPackage(pkg) &&
            !FreezerManager.isAppCurrentlyVisible(pkg) &&
            !FreezerManager.isRecentlyLaunched(pkg, 30000L)
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
                    val isSpecial = specialApps.contains(pkg)
                    val isFgsImmune = FreezerManager.isFgsImmunityEnabled(context, pkg)
                    val isDownloading = if (!isSpecial && isFgsImmune) hasActiveForegroundTask(pkg) else false
                    if (!isAudio && !isSafe && !isDownloading) {
                        Log.d(TAG, "❄️ Freezing swiped away app: $pkg (isSpecial=$isSpecial, isFgsImmune=$isFgsImmune)")
                        FreezerManager.freezeApp(context, pkg, force = true, isExplicitDismiss = true)
                    } else if (isDownloading) {
                        Log.d(TAG, "🛡️ Smart FGS Guard: App $pkg swiped away but has FGS immunity & active task -> Freeze BLOCKED!")
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
            // 🛡️ CRITICAL GUARD: Never sweep apps recently launched, in active session, or in recents cache!
            if (FreezerManager.isRecentlyLaunched(pkg, 30000L) ||
                FreezerManager.isAppActiveSession(pkg) ||
                isAppInRecents(pkg)) {
                continue
            }

            if (!currentRecents.contains(pkg) && pkg != currentForeground && !FreezerManager.isAppCurrentlyVisible(pkg)) {
                val isAudio = FreezerManager.getActivePlayingAudioPackages(context).contains(pkg)
                val isSafe = MultitaskingManager.getUserWhitelist(context).contains(pkg) ||
                             MultitaskingManager.protectedApps.contains(pkg)
                val isSpecial = specialApps.contains(pkg)
                val isFgsImmune = FreezerManager.isFgsImmunityEnabled(context, pkg)
                val isDownloading = if (!isSpecial && isFgsImmune) hasActiveForegroundTask(pkg) else false
                if (!isAudio && !isSafe && !isDownloading) {
                    Log.d(TAG, "❄️ Freezing orphan background process not in Recents: $pkg (isSpecial=$isSpecial, isFgsImmune=$isFgsImmune)")
                    FreezerManager.freezeApp(context, pkg, force = true, isExplicitDismiss = true)
                } else if (isDownloading) {
                    Log.d(TAG, "🛡️ Smart FGS Guard: Skipping orphan sweep for $pkg (active FGS & download immunity in progress)")
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
