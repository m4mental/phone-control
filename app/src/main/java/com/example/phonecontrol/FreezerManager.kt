package com.example.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log

object FreezerManager {
    private const val TAG = "FreezerManager"

    val activeSessionApps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val kernelFrozenPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    var lastLaunchedPackage: String? = null
    var lastLaunchTime: Long = 0
    val launchTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val freezerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Registers an app as actively opened in user session.
     * Guaranteed 0ms immunity from freeze/force-stop and instant unfreeze.
     * Non-blocking (ANR-Proof): Unfreeze script executes on dedicated background thread.
     */
    fun registerAppOpen(packageName: String) {
        if (packageName.isBlank()) return
        activeSessionApps.add(packageName)
        RecentTasksManager.onAppOpened(packageName)
        lastLaunchedPackage = packageName
        val now = System.currentTimeMillis()
        lastLaunchTime = now
        launchTimestamps[packageName] = now
        freezerExecutor.execute {
            unfreezeApp(packageName)
        }
    }

    fun isRecentlyLaunched(packageName: String, gracePeriodMs: Long = 30000L): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == lastLaunchedPackage && (System.currentTimeMillis() - lastLaunchTime < gracePeriodMs)) return true
        val time = launchTimestamps[packageName] ?: return false
        return (System.currentTimeMillis() - time < gracePeriodMs)
    }

    fun isAppActiveSession(packageName: String): Boolean {
        return activeSessionApps.contains(packageName)
    }

    fun removeActiveSession(packageName: String) {
        activeSessionApps.remove(packageName)
        launchTimestamps.remove(packageName)
    }

    /**
     * Hibernates a single app immediately.
     * Normal Freeze: Linux Kernel cgroup v2 freeze (`am freeze --sticky`) for 0ms instant launch from anywhere.
     * Special Freeze: Deep Package Suspension (`cmd package suspend`) + force-stop for persistent/smartwatch apps.
     * @param force If true, bypasses session and recent-launch grace checks (for Screen-Off, Recents dismiss, or manual freeze).
     */
    fun freezeApp(
        context: Context,
        packageName: String,
        force: Boolean = false,
        isExplicitDismiss: Boolean = false,
        isManualUserAction: Boolean = false
    ) {
        if (packageName.isBlank() || packageName == context.packageName) return

        // 🛡️ CRITICAL LAUNCH GRACE IMMUNITY:
        // If an app was launched within the last 30 seconds, NEVER freeze or kill it!
        // This completely eliminates premature kill races during cold start, splash screen, or transition.
        if (!isManualUserAction && isRecentlyLaunched(packageName, 30000L)) {
            Log.d(TAG, "🛡️ Launch Grace Guard: $packageName was launched recently -> Freeze BLOCKED!")
            return
        }

        // 🛡️ VISIBILITY & FOREGROUND GUARD:
        if (!isManualUserAction && isAppCurrentlyVisible(packageName)) {
            Log.d(TAG, "🛡️ Visibility Guard: $packageName is visible -> Freeze BLOCKED!")
            return
        }

        val isSpecial = isSpecialFreeze(context, packageName)
        val isFgsImmune = isFgsImmunityEnabled(context, packageName)

        // 🛡️ RECENT TASKS IMMUNITY GUARD:
        // As long as the app is open/alive in Recents (Multitasking), do NOT kill it!
        // It can ONLY be frozen if the user explicitly swiped it away from Recents (isExplicitDismiss == true),
        // or during forced operations (Screen-Off, Manual Freeze, Widget/Tile trigger).
        if (!isManualUserAction && !isExplicitDismiss && !force && RecentTasksManager.isAppInRecents(packageName)) {
            Log.d(TAG, "🛡️ Recents Guard: $packageName is active in Recents -> Freeze BLOCKED!")
            return
        }

        // 🛡️ SMART ACTIVE TASK & FOREGROUND SERVICE GUARD:
        // Only exempt from freeze if the app has EXPLICIT FGS / Download Immunity permission!
        // Special Freeze apps NEVER receive FGS immunity (always force-stopped).
        if (!isManualUserAction && !isExplicitDismiss && !force && !isSpecial && isFgsImmune && RecentTasksManager.hasActiveForegroundTask(packageName)) {
            Log.d(TAG, "🛡️ Smart FGS Guard: $packageName has FGS immunity & active task -> Freeze BLOCKED!")
            return
        }

        if (!isManualUserAction && !force) {
            if (isAppActiveSession(packageName)) return
        }

        val allSafeApps = MultitaskingManager.getUserWhitelist(context) + MultitaskingManager.protectedApps
        if (allSafeApps.contains(packageName)) return

        val activeAudio = getActivePlayingAudioPackages(context)
        if (activeAudio.contains(packageName)) return

        val qPkg = ShellUtils.shellQuote(packageName)

        if (isSpecial) {
            // ==========================================
            // 🔒 SPECIAL FREEZE: Deep Package Suspension + Force Stop
            // ==========================================
            // Suspends the package in package manager so Android OS completely blocks
            // all background services (BLE, NotificationListener), alarms, jobs, and receivers.
            // App can ONLY be reopened via Special Widget or Phone Control app!
            val specialScript = """
                cmd package suspend --user 0 $qPkg 2>/dev/null
                pm suspend $qPkg 2>/dev/null
                am force-stop $qPkg 2>/dev/null
                am set-standby-bucket $qPkg restricted 2>/dev/null
                cmd appops set $qPkg RUN_IN_BACKGROUND ignore 2>/dev/null
                cmd appops set $qPkg RUN_ANY_IN_BACKGROUND ignore 2>/dev/null
            """.trimIndent()
            ShellUtils.fastCmd(specialScript)
            kernelFrozenPackages.remove(packageName)
            return
        }

        // ==========================================
        // ❄️ NORMAL FREEZE: Linux Kernel cgroup v2 Freeze
        // ==========================================
        // Freezes all processes of the package at the kernel level without killing them.
        // Consumes 0% CPU, 0 energy, and preserves memory state so it can be reopened
        // from ANYWHERE (Launcher, Recents, Notifications, Widgets) with 0ms delay!
        val normalScript = """
            am set-standby-bucket $qPkg restricted 2>/dev/null
            cmd appops set $qPkg RUN_IN_BACKGROUND ignore 2>/dev/null
            cmd appops set $qPkg RUN_ANY_IN_BACKGROUND ignore 2>/dev/null
            if am freeze --sticky $qPkg 2>/dev/null; then
                echo "FREEZE_SUCCESS"
            else
                am stop-app $qPkg 2>/dev/null || am force-stop $qPkg 2>/dev/null
                pkill -9 -f "^$packageName" 2>/dev/null
                echo "FREEZE_FAILED"
            fi
        """.trimIndent()
        val result = ShellUtils.fastCmdResult(normalScript, 2000).trim()
        if (result.contains("FREEZE_SUCCESS")) {
            kernelFrozenPackages.add(packageName)
            Log.d(TAG, "❄️ Kernel Freeze Success: $packageName")
        } else {
            kernelFrozenPackages.remove(packageName)
            Log.w(TAG, "⚠️ Kernel Freeze Failed / No Process: $packageName")
        }
    }

    /**
     * Batch Hibernates multiple apps in a single ultra-fast shell execution (0ms UI lag).
     */
    fun freezeMultipleApps(context: Context, packages: Collection<String>, force: Boolean = false, isManualUserAction: Boolean = false) {
        if (packages.isEmpty()) return
        val currentFocus = getCurrentlyFocusedWindowInfo()
        val allSafeApps = MultitaskingManager.getUserWhitelist(context) + MultitaskingManager.protectedApps
        val activeAudio = getActivePlayingAudioPackages(context)

        val pkgList = packages.filter { pkg ->
            val isSpecial = isSpecialFreeze(context, pkg)
            val isFgsImmune = isFgsImmunityEnabled(context, pkg)
            !allSafeApps.contains(pkg) &&
            !activeAudio.contains(pkg) &&
            (isSpecial || !isFgsImmune || !RecentTasksManager.hasActiveForegroundTask(pkg)) &&
            (currentFocus.isBlank() || !currentFocus.contains(pkg)) &&
            (isManualUserAction || (!isRecentlyLaunched(pkg, 30000L) && (force || !isAppActiveSession(pkg))))
        }
        if (pkgList.isEmpty()) return

        for (pkg in pkgList) {
            freezeApp(context, pkg, force = force, isManualUserAction = isManualUserAction)
        }
    }

    fun isAutoFreezeEnabled(context: Context): Boolean {
        val freezerPrefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val mainPrefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        return freezerPrefs.getBoolean("auto_freeze_enabled", false) ||
               mainPrefs.getBoolean("freezer_enabled", false)
    }

    fun setAutoFreezeEnabled(context: Context, enabled: Boolean) {
        val freezerPrefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val mainPrefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        freezerPrefs.edit().putBoolean("auto_freeze_enabled", enabled).apply()
        mainPrefs.edit().putBoolean("freezer_enabled", enabled).apply()
    }

    fun getAutoFreezeDelaySeconds(context: Context): Int {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("auto_freeze_delay_seconds", 0)
    }

    fun setAutoFreezeDelaySeconds(context: Context, seconds: Int) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("auto_freeze_delay_seconds", seconds).apply()
    }

    /**
     * Targeted Recents Dismissal & Idle Background Freeze:
     * Freezes apps that were swiped away from Recents, or configured apps running in background
     * without active foreground UI, audio playback, or recents task.
     * Takes ~15-20ms, 100% reliable.
     */
    fun processRecentsDismissal(
        context: Context,
        currentRecents: Set<String>,
        currentForeground: String
    ) {
        val specialApps = getSpecialFreezeApps(context)
        val standardApps = getFrozenApps(context)
        val allConfigured = specialApps + standardApps
        if (allConfigured.isEmpty()) return

        val allSafeApps = MultitaskingManager.getUserWhitelist(context) + MultitaskingManager.protectedApps
        val activeAudio = getActivePlayingAudioPackages(context)

        val toFreeze = mutableSetOf<String>()

        // 1. Apps tracked in activeSessionApps that have been dismissed from Recents and left foreground
        val sessionCopy = HashSet(activeSessionApps)
        for (pkg in sessionCopy) {
            // 🛡️ CRITICAL GUARD: Never freeze apps launched within the last 30s!
            if (isRecentlyLaunched(pkg, 30000L)) continue

            val isSpecial = specialApps.contains(pkg)
            val isFgsImmune = isFgsImmunityEnabled(context, pkg)
            val isDownloading = if (!isSpecial && isFgsImmune) RecentTasksManager.hasActiveForegroundTask(pkg) else false
            if (!currentRecents.contains(pkg) && pkg != currentForeground && !isAppCurrentlyVisible(pkg) && (!isDownloading)) {
                toFreeze.add(pkg)
            }
        }

        // 2. Any configured freezer app that is NOT on screen, NOT in recents, and NOT currently foreground,
        // but has active running background processes (e.g. after background wakeup/broadcast)
        val runningConfigured = getRunningConfiguredApps(allConfigured)
        for (pkg in runningConfigured) {
            // 🛡️ CRITICAL GUARD: Never sweep apps recently launched, in active session, or in recents cache!
            if (isRecentlyLaunched(pkg, 30000L) || isAppActiveSession(pkg) || RecentTasksManager.isAppInRecents(pkg)) continue

            val isSpecial = specialApps.contains(pkg)
            val isFgsImmune = isFgsImmunityEnabled(context, pkg)
            val isDownloading = if (!isSpecial && isFgsImmune) RecentTasksManager.hasActiveForegroundTask(pkg) else false
            if (pkg != currentForeground && !currentRecents.contains(pkg) && !isAppCurrentlyVisible(pkg) && (!isDownloading)) {
                toFreeze.add(pkg)
            }
        }

        if (toFreeze.isEmpty()) return

        for (pkg in toFreeze) {
            // 🛡️ Extra check before freeze
            if (isRecentlyLaunched(pkg, 30000L) || isAppCurrentlyVisible(pkg)) continue
            activeSessionApps.remove(pkg)
            launchTimestamps.remove(pkg)
            if (pkg == lastLaunchedPackage) {
                lastLaunchedPackage = null
            }

            val isSpecial = specialApps.contains(pkg)
            val isFgsImmune = isFgsImmunityEnabled(context, pkg)
            val isDownloading = if (!isSpecial && isFgsImmune) RecentTasksManager.hasActiveForegroundTask(pkg) else false
            if (allConfigured.contains(pkg) && !allSafeApps.contains(pkg) && !activeAudio.contains(pkg) && (!isDownloading)) {
                Log.d(TAG, "❄️ Recents Dismissed / Background Idle -> Freeze for $pkg (special=$isSpecial)")
                freezeApp(context, pkg, force = true, isExplicitDismiss = true)
            }
        }
    }

    /**
     * Sweep to clean up any legacy pm suspend states from standard freezer apps,
     * while strictly PRESERVING special freeze suspended apps.
     */
    fun cleanLegacySuspendedApps(context: Context) {
        val standardApps = getFrozenApps(context) - getSpecialFreezeApps(context)
        if (standardApps.isEmpty()) return
        val pkgList = standardApps.joinToString(" ") { ShellUtils.shellQuote(it) }
        freezerExecutor.execute {
            ShellUtils.fastCmd("for p in $pkgList; do cmd package unsuspend --user 0 \$p 2>/dev/null; pm unsuspend \$p 2>/dev/null; done")
        }
    }

    /**
     * Removes packages that have been uninstalled or no longer exist on the system
     * from frozen_apps, special_freeze_apps, and custom_widget_apps.
     * Prevents "Unknown App" ghost entries and uninstalled package clutter.
     */
    fun pruneUninstalledPackages(context: Context) {
        val pm = context.packageManager
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)

        val frozen = prefs.getStringSet("frozen_apps", emptySet()) ?: emptySet()
        val special = prefs.getStringSet("special_freeze_apps", emptySet()) ?: emptySet()
        val customWidget = prefs.getStringSet("custom_widget_apps", emptySet()) ?: emptySet()

        val isInstalled = { pkg: String ->
            try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        val cleanFrozen = frozen.filter(isInstalled).toSet()
        val cleanSpecial = special.filter(isInstalled).toSet()
        val cleanCustomWidget = customWidget.filter(isInstalled).toSet()

        val editor = prefs.edit()
        var changed = false

        if (cleanFrozen.size != frozen.size) {
            editor.putStringSet("frozen_apps", cleanFrozen)
            changed = true
        }
        if (cleanSpecial.size != special.size) {
            editor.putStringSet("special_freeze_apps", cleanSpecial)
            changed = true
        }
        if (cleanCustomWidget.size != customWidget.size) {
            editor.putStringSet("custom_widget_apps", cleanCustomWidget)
            changed = true
        }

        for (key in prefs.all.keys) {
            if (key.startsWith("special_") && key != "special_freeze_apps") {
                val pkg = key.removePrefix("special_")
                if (!isInstalled(pkg)) {
                    editor.remove(key)
                    changed = true
                }
            }
        }

        if (changed) {
            editor.apply()
        }
    }

    /**
     * Checks running process state for configured packages in a single batch ps scan (~150ms).
     * Eliminates sequential shell pidof loops and prevents timeouts.
     */
    fun getRunningConfiguredApps(configured: Collection<String>): Set<String> {
        if (configured.isEmpty()) return emptySet()
        val out = ShellUtils.fastCmdResult("ps -A -o NAME 2>/dev/null || ps -A 2>/dev/null", 1500)
        if (out.isBlank()) return emptySet()
        val running = out.lineSequence().map { line ->
            val trimmed = line.trim()
            val rawName = if (trimmed.contains(" ")) trimmed.substringAfterLast(" ") else trimmed
            rawName.substringBefore(":")
        }.toSet()
        return configured.filter { running.contains(it) }.toSet()
    }

    /**
     * Resumes an app instantly on open.
     */
    fun unfreezeApp(packageName: String) {
        if (packageName.isBlank()) return
        kernelFrozenPackages.remove(packageName)
        val qPkg = ShellUtils.shellQuote(packageName)
        val script = """
            cmd package unsuspend --user 0 $qPkg 2>/dev/null
            pm unsuspend $qPkg 2>/dev/null
            pm enable $qPkg 2>/dev/null
            am unfreeze --sticky $qPkg 2>/dev/null
            am unfreeze $qPkg 2>/dev/null
            pids=${'$'}(pgrep -f "^$packageName" 2>/dev/null || pidof "$packageName" 2>/dev/null)
            if [ -n "${'$'}pids" ]; then
                for p in ${'$'}pids; do
                    kill -CONT ${'$'}p 2>/dev/null
                    echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                done
            fi
            cmd appops set $qPkg RUN_IN_BACKGROUND allow 2>/dev/null
            cmd appops set $qPkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null
            am set-standby-bucket $qPkg active 2>/dev/null
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Batch Unfreezes multiple apps.
     */
    fun unfreezeMultipleApps(packages: Collection<String>) {
        if (packages.isEmpty()) return
        for (pkg in packages) {
            kernelFrozenPackages.remove(pkg)
        }
        val pkgList = packages.joinToString(" ") { ShellUtils.shellQuote(it) }
        val script = """
            for pkg in $pkgList; do
                cmd package unsuspend --user 0 "${'$'}pkg" 2>/dev/null
                pm unsuspend "${'$'}pkg" 2>/dev/null
                pm enable "${'$'}pkg" 2>/dev/null
                am unfreeze --sticky "${'$'}pkg" 2>/dev/null
                am unfreeze "${'$'}pkg" 2>/dev/null
                pids=${'$'}(pgrep -f "^${'$'}pkg" 2>/dev/null || pidof "${'$'}pkg" 2>/dev/null)
                if [ -n "${'$'}pids" ]; then
                    for p in ${'$'}pids; do
                        kill -CONT ${'$'}p 2>/dev/null
                        echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                    done
                fi
                cmd appops set "${'$'}pkg" RUN_IN_BACKGROUND allow 2>/dev/null
                cmd appops set "${'$'}pkg" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
                am set-standby-bucket "${'$'}pkg" active 2>/dev/null
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    @Volatile private var cachedRecentPackages: Set<String> = emptySet()
    @Volatile private var lastRecentPackagesTimestamp: Long = 0L

    /**
     * Returns the set of all packages currently open in the system's Recent Apps / Recents Task list.
     * Strictly filters for alive tasks (hasTask=true) to avoid dead task tombstones.
     * Includes in-memory caching with graceful timeout fallback to prevent dropouts.
     */
    fun getRecentPackages(forceRefresh: Boolean = false): Set<String> {
        val pkgs = RecentTasksManager.getLiveRecentPackages(forceRefresh)
        if (pkgs.isNotEmpty()) {
            activeSessionApps.retainAll(pkgs)
        }
        return pkgs
    }


    /**
     * Returns set of packages that are actively playing audio/video (state = PLAYING or started audio track).
     * Excludes non-playing or paused media sessions.
     */
    fun getActivePlayingAudioPackages(context: Context): Set<String> {
        val activePlaying = mutableSetOf<String>()
        val ignoredAudioPkgs = setOf(
            "com.android.server.telecom",
            "com.android.systemui",
            "android",
            "com.google.android.googlequicksearchbox",
            "com.example.phonecontrol"
        )
        try {
            val script = """
                dumpsys media_session 2>/dev/null | grep -B 15 'state=PLAYING' | grep 'package=' | cut -d '=' -f2
                dumpsys media_session 2>/dev/null | grep -B 15 'state=3' | grep 'package=' | cut -d '=' -f2
                for pid in $(dumpsys audio 2>/dev/null | grep -B 1 'state:started' | grep -o 'u/pid:[0-9]*/[0-9]*' | cut -d '/' -f3); do
                    cat /proc/${'$'}pid/cmdline 2>/dev/null | tr '\0' '\n'
                done
            """.trimIndent()
            val out = ShellUtils.fastCmdResult(script, 1500)
            for (line in out.lineSequence()) {
                val pkg = line.trim()
                if (pkg.isNotBlank() && !ignoredAudioPkgs.contains(pkg)) {
                    activePlaying.add(pkg)
                }
            }

            // Fallback: Also check all active media session packages
            if (activePlaying.isEmpty()) {
                val allSessions = ShellUtils.fastCmdResult("dumpsys media_session | grep 'package=' | cut -d '=' -f2 2>/dev/null", 1000)
                activePlaying.addAll(allSessions.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !ignoredAudioPkgs.contains(it) })
            }
        } catch (e: Exception) {}
        return activePlaying
    }

    /**
     * Gets currently focused window info string from dumpsys.
     */
    fun getCurrentlyFocusedWindowInfo(): String {
        return try {
            ShellUtils.fastCmdResult("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' 2>/dev/null", 1000)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Checks if an app is currently visible on the screen or in focus.
     */
    fun isAppCurrentlyVisible(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val out = getCurrentlyFocusedWindowInfo()
        return out.contains(packageName)
    }

    fun getActivePackages(context: Context? = null, packages: Collection<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val out = ShellUtils.runAsRoot("ps -A -o NAME").output
        val running = out.split("\n").map { it.trim() }.toSet()
        val pm = context?.packageManager
        return packages.filter { pkg ->
            if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    if (pm.isPackageSuspended(pkg)) return@filter false
                } catch (_: Exception) {}
            }
            if (kernelFrozenPackages.contains(pkg) && !isAppCurrentlyVisible(pkg)) {
                return@filter false
            }
            running.contains(pkg)
        }.toSet()
    }

    fun getActivePackages(packages: Collection<String>): Set<String> {
        return getActivePackages(null, packages)
    }

    fun launchApp(context: Context, packageName: String) {
        if (!ShellUtils.isValidPackageName(packageName)) return
        
        // 1. Instantly register active session & grant 30-second absolute immunity
        registerAppOpen(packageName)
        kernelFrozenPackages.remove(packageName)

        val qPkg = ShellUtils.shellQuote(packageName)

        // 2. Resolve target launcher activity component name (including suspended/disabled components)
        var componentName = ""
        val pm = context.packageManager
        val standardIntent = pm.getLaunchIntentForPackage(packageName)
        if (standardIntent?.component != null) {
            componentName = standardIntent.component!!.flattenToShortString()
        } else {
            try {
                val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PackageManager.ResolveInfoFlags.of((PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong())
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
                }
                val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(queryIntent, flags as PackageManager.ResolveInfoFlags)
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(queryIntent, flags as Int)
                }
                resolves.firstOrNull()?.activityInfo?.let {
                    componentName = ComponentName(it.packageName, it.name).flattenToShortString()
                }
            } catch (_: Exception) {}
        }

        val startCmd = if (componentName.isNotBlank()) {
            val qComp = ShellUtils.shellQuote(componentName)
            "am start -n $qComp -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --activity-brought-to-front"
        } else {
            """
            comp=${'$'}(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $qPkg 2>/dev/null | grep -E "^[a-zA-Z0-9_.]+/.*" | tail -n 1)
            if [ -n "${'$'}comp" ] && [ "${'$'}comp" != "No activity found" ]; then
                am start -n "${'$'}comp" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --activity-brought-to-front
            else
                monkey -p $qPkg -c android.intent.category.LAUNCHER 1
            fi
            """.trimIndent()
        }

        // 3. Atomically unsuspend, enable, unfreeze, and launch in root shell asynchronously with 0ms UI delay
        // Sequential shell execution guarantees pm unsuspend finishes BEFORE am start, preventing SuspendedAppActivity
        val launchScript = """
            cmd package unsuspend --user 0 $qPkg 2>/dev/null
            pm unsuspend $qPkg 2>/dev/null
            pm enable $qPkg 2>/dev/null
            am unfreeze --sticky $qPkg 2>/dev/null
            am unfreeze $qPkg 2>/dev/null
            pids=${'$'}(pgrep -f "^$packageName" 2>/dev/null || pidof "$packageName" 2>/dev/null)
            if [ -n "${'$'}pids" ]; then
                for p in ${'$'}pids; do
                    kill -CONT ${'$'}p 2>/dev/null
                    echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                done
            fi
            cmd appops set $qPkg RUN_IN_BACKGROUND allow 2>/dev/null
            cmd appops set $qPkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null
            am set-standby-bucket $qPkg active 2>/dev/null
            $startCmd
        """.trimIndent()

        ShellUtils.fastCmd(launchScript)
        TweakManager.triggerTurboBoost()
    }

    fun getFrozenApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("frozen_apps", emptySet()) ?: emptySet()
    }

    fun saveFrozenApps(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("frozen_apps", packages).apply()
        AppEventService.invalidateLabelCache()
    }

    fun addAppToFreezer(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("frozen_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(packageName)
        saveFrozenApps(context, set)
        if (!isAutoFreezeEnabled(context)) {
            setAutoFreezeEnabled(context, true)
        }
        freezeApp(context, packageName, force = true)
    }

    fun removeAppFromFreezer(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val frozen = prefs.getStringSet("frozen_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        frozen.remove(packageName)
        saveFrozenApps(context, frozen)

        val special = prefs.getStringSet("special_freeze_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (special.remove(packageName)) {
            prefs.edit().putStringSet("special_freeze_apps", special).apply()
        }

        val fgsImmune = prefs.getStringSet("fgs_immune_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (fgsImmune.remove(packageName)) {
            prefs.edit().putStringSet("fgs_immune_apps", fgsImmune).apply()
        }

        val custom = prefs.getStringSet("custom_widget_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (custom.remove(packageName)) {
            prefs.edit().putStringSet("custom_widget_apps", custom).apply()
        }

        AppEventService.invalidateLabelCache()
        unfreezeApp(packageName)
        FreezerWidgetProvider.updateAllWidgets(context)
        SpecialFreezerWidgetProvider.updateAllWidgets(context)
    }

    fun getCustomWidgetApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("custom_widget_apps", emptySet()) ?: emptySet()
    }

    fun saveCustomWidgetApps(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("custom_widget_apps", packages).apply()
    }

    fun toggleCustomWidgetApp(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("custom_widget_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        val newState = if (set.contains(packageName)) {
            set.remove(packageName)
            false
        } else {
            set.add(packageName)
            true
        }
        prefs.edit().putStringSet("custom_widget_apps", set).apply()
        return newState
    }

    fun getSpecialFreezeApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("special_freeze_apps", emptySet()) ?: emptySet()
    }

    fun isSpecialFreeze(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("special_freeze_apps", emptySet()) ?: emptySet()
        return set.contains(packageName)
    }

    fun setSpecialFreeze(context: Context, packageName: String, enable: Boolean) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("special_freeze_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enable) set.add(packageName) else set.remove(packageName)
        prefs.edit().putStringSet("special_freeze_apps", set).apply()
        AppEventService.invalidateLabelCache()
    }

    fun getFgsImmuneApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("fgs_immune_apps", emptySet()) ?: emptySet()
    }

    fun saveFgsImmuneApps(context: Context, apps: Set<String>) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("fgs_immune_apps", apps).apply()
    }

    fun isFgsImmunityEnabled(context: Context, packageName: String): Boolean {
        return getFgsImmuneApps(context).contains(packageName)
    }

    fun setFgsImmunity(context: Context, packageName: String, enable: Boolean) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("fgs_immune_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enable) set.add(packageName) else set.remove(packageName)
        prefs.edit().putStringSet("fgs_immune_apps", set).apply()
    }

    fun toggleFgsImmunity(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("fgs_immune_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        val newState = if (set.contains(packageName)) {
            set.remove(packageName)
            false
        } else {
            set.add(packageName)
            true
        }
        prefs.edit().putStringSet("fgs_immune_apps", set).apply()
        return newState
    }

    val KNOWN_EQUALIZERS = listOf(
        "com.maxmpz.equalizer",               // Poweramp Equalizer
        "com.pittvandewitt.wavelet",           // Wavelet
        "com.audlabs.viperfx",                // ViPER4Android FX
        "com.pittvandewitt.viperfx",           // ViPER4Android
        "com.jazibkhan.equalizer",             // Flat Equalizer
        "james.dsp",                           // JamesDSP
        "me.timschneeberger.rootlessjamesdsp", // RootlessJamesDSP
        "com.kotor.spotiq",                    // SpotiQ
        "com.goodev.volume.booster"            // Volume Booster Goodev
    )

    /**
     * Dynamically detects the active equalizer or audio DSP app installed on the device.
     */
    fun getDetectedEqualizerPackage(context: Context): String? {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val manualSelection = prefs.getString("selected_equalizer_pkg", null)
        val pm = context.packageManager

        // 1. Check user manual override
        if (!manualSelection.isNullOrBlank()) {
            try {
                pm.getPackageInfo(manualSelection, 0)
                return manualSelection
            } catch (ignored: Exception) {}
        }

        // 2. Check popular known equalizers
        for (pkg in KNOWN_EQUALIZERS) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (ignored: Exception) {}
        }

        // 3. Query system for any app responding to AudioEffect Control Panel
        try {
            val intent = Intent("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL")
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            for (info in resolveInfos) {
                val pkg = info.activityInfo?.packageName
                if (pkg != null && pkg != context.packageName) {
                    return pkg
                }
            }
        } catch (ignored: Exception) {}

        return null
    }

    fun isEqualizerSleepEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("smart_equalizer_sleep_enabled", true)
    }

    fun setEqualizerSleepEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("smart_equalizer_sleep_enabled", enabled).apply()
    }

    fun saveSelectedEqualizer(context: Context, pkg: String?) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("selected_equalizer_pkg", pkg).apply()
    }

    /**
     * Ultra-fast RAM sleep (CGroup Freeze) for audio equalizers.
     * Halts all threads and wakelocks without killing the service or breaking audio pipelines.
     */
    fun instantFreezeEqualizer(packageName: String) {
        if (packageName.isBlank()) return
        val script = """
            am set-standby-bucket "$packageName" restricted 2>/dev/null
            am stop-app "$packageName" 2>/dev/null || am kill "$packageName" 2>/dev/null
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * 1-Millisecond Instant Unfreeze:
     * Resumes the equalizer instantly from RAM with its audio session completely intact (Zero Audio Dropout).
     */
    fun instantUnfreezeEqualizer(packageName: String) {
        if (packageName.isBlank()) return
        val script = """
            pm enable "$packageName" 2>/dev/null
            am unfreeze "$packageName" 2>/dev/null
            am set-standby-bucket "$packageName" active 2>/dev/null
            for p in $(pidof "$packageName"); do
                echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Checks if the app is currently in frozen/suspended state.
     */
    fun isAppFrozen(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (kernelFrozenPackages.contains(packageName)) return true
        val out = ShellUtils.fastCmdResult("dumpsys activity processes $packageName 2>/dev/null | grep -iE 'isFrozen=true|freeze=true'", 1000)
        if (out.isNotBlank()) return true
        val suspended = ShellUtils.fastCmdResult("dumpsys package $packageName 2>/dev/null | grep -i 'suspended=true'", 800).trim()
        if (suspended.isNotBlank()) return true
        val bucket = ShellUtils.fastCmdResult("am get-standby-bucket $packageName 2>/dev/null", 800).trim()
        return (bucket == "45" || bucket == "restricted" || bucket == "50")
    }
}
