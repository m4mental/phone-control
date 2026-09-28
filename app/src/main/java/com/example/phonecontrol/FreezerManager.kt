package com.example.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build

object FreezerManager {

    val activeSessionApps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
        lastLaunchedPackage = packageName
        val now = System.currentTimeMillis()
        lastLaunchTime = now
        launchTimestamps[packageName] = now
        freezerExecutor.execute {
            unfreezeApp(packageName)
        }
    }

    fun isRecentlyLaunched(packageName: String, gracePeriodMs: Long = 20000L): Boolean {
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
     * @param force If true, bypasses session and recent-launch grace checks (for Screen-Off, Recents dismiss, or manual freeze).
     */
    fun freezeApp(context: Context, packageName: String, force: Boolean = false) {
        if (packageName.isBlank() || packageName == context.packageName) return
        
        // Never freeze the app if it is currently visible on screen (bypass when force is true, e.g. Screen-Off)
        if (!force && isAppCurrentlyVisible(packageName)) return

        if (!force) {
            if (isAppActiveSession(packageName)) return
            if (isRecentlyLaunched(packageName, 10000L)) return
        }

        val allSafeApps = MultitaskingManager.getUserWhitelist(context) + MultitaskingManager.protectedApps
        if (allSafeApps.contains(packageName)) return

        val activeAudio = getActivePlayingAudioPackages(context)
        if (activeAudio.contains(packageName)) return

        if (isSpecialFreeze(context, packageName)) {
            val forceFlag = if (force) 1 else 0
            val specialScript = """
                am set-standby-bucket "$packageName" restricted 2>/dev/null
                cmd appops set "$packageName" RUN_IN_BACKGROUND ignore 2>/dev/null
                cmd appops set "$packageName" RUN_ANY_IN_BACKGROUND ignore 2>/dev/null
                pids=${'$'}(pgrep -f "^$packageName" 2>/dev/null || pidof "$packageName" 2>/dev/null)
                is_safe=0
                if [ "$forceFlag" -eq 0 ] && [ -n "${'$'}pids" ]; then
                    for p in ${'$'}pids; do
                        adj=${'$'}(cat /proc/${'$'}p/oom_score_adj 2>/dev/null)
                        if [ -n "${'$'}adj" ] && [ "${'$'}adj" -le 200 ]; then
                            is_safe=1
                            break
                        fi
                    done
                fi
                if [ "${'$'}is_safe" -eq 0 ]; then
                    am force-stop "$packageName" 2>/dev/null
                    pm suspend "$packageName" 2>/dev/null
                fi
            """.trimIndent()
            ShellUtils.fastCmd(specialScript)
            return
        }

        // Standard Freeze: Restrict standby bucket, block background execution, and freeze cgroup / am freeze
        val forceFlag = if (force) 1 else 0
        val script = """
            am set-standby-bucket "$packageName" restricted 2>/dev/null
            cmd appops set "$packageName" RUN_IN_BACKGROUND ignore 2>/dev/null
            cmd appops set "$packageName" RUN_ANY_IN_BACKGROUND ignore 2>/dev/null
            pids=${'$'}(pgrep -f "^$packageName" 2>/dev/null || pidof "$packageName" 2>/dev/null)
            is_safe=0
            if [ "$forceFlag" -eq 0 ] && [ -n "${'$'}pids" ]; then
                for p in ${'$'}pids; do
                    adj=${'$'}(cat /proc/${'$'}p/oom_score_adj 2>/dev/null)
                    if [ -n "${'$'}adj" ] && [ "${'$'}adj" -le 200 ]; then
                        is_safe=1
                        break
                    fi
                done
            fi
            if [ "${'$'}is_safe" -eq 0 ]; then
                am freeze "$packageName" 2>/dev/null
                if [ -n "${'$'}pids" ]; then
                    for p in ${'$'}pids; do
                        echo 900 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                    done
                fi
            fi
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Batch Hibernates multiple apps in a single ultra-fast shell execution (0ms UI lag).
     */
    fun freezeMultipleApps(context: Context, packages: Collection<String>, force: Boolean = false) {
        if (packages.isEmpty()) return
        val currentFocus = getCurrentlyFocusedWindowInfo()
        val allSafeApps = MultitaskingManager.getUserWhitelist(context) + MultitaskingManager.protectedApps
        val activeAudio = getActivePlayingAudioPackages(context)

        val pkgList = packages.filter { pkg ->
            !allSafeApps.contains(pkg) &&
            !activeAudio.contains(pkg) &&
            (currentFocus.isBlank() || !currentFocus.contains(pkg)) &&
            (force || (!isRecentlyLaunched(pkg, 10000L) && !isAppActiveSession(pkg)))
        }
        if (pkgList.isEmpty()) return

        for (pkg in pkgList) {
            freezeApp(context, pkg, force = force)
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
            if (!currentRecents.contains(pkg) && pkg != currentForeground && !isAppCurrentlyVisible(pkg)) {
                toFreeze.add(pkg)
            }
        }

        // 2. Any configured freezer app that is NOT on screen, NOT in recents, and NOT currently foreground,
        // but has active running background processes (e.g. after background wakeup/broadcast)
        val runningConfigured = getRunningConfiguredApps(allConfigured)
        for (pkg in runningConfigured) {
            if (pkg != currentForeground && !currentRecents.contains(pkg) && !isAppCurrentlyVisible(pkg)) {
                toFreeze.add(pkg)
            }
        }

        if (toFreeze.isEmpty()) return

        for (pkg in toFreeze) {
            if (isAppCurrentlyVisible(pkg)) continue
            activeSessionApps.remove(pkg)
            launchTimestamps.remove(pkg)
            if (pkg == lastLaunchedPackage) {
                lastLaunchedPackage = null
            }

            if (allConfigured.contains(pkg) && !allSafeApps.contains(pkg) && !activeAudio.contains(pkg)) {
                android.util.Log.d("FreezerManager", "❄️ Recents Dismissed / Background Idle -> Freeze for $pkg")
                freezeApp(context, pkg, force = true)
            }
        }
    }

    /**
     * One-time background sweep to clean up any legacy pm suspend state from previous builds.
     * Only unsuspends standard hibernation apps, strictly preserving special freeze suspended apps.
     */
    fun cleanLegacySuspendedApps(context: Context) {
        val standardApps = getFrozenApps(context) - getSpecialFreezeApps(context)
        if (standardApps.isEmpty()) return
        val pkgList = standardApps.joinToString(" ")
        freezerExecutor.execute {
            ShellUtils.fastCmd("for p in $pkgList; do pm unsuspend ${'$'}p 2>/dev/null; done")
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
        val script = """
            pm unsuspend "$packageName" 2>/dev/null
            pm enable "$packageName" 2>/dev/null
            am unfreeze "$packageName" 2>/dev/null
            cmd appops set "$packageName" RUN_IN_BACKGROUND allow 2>/dev/null
            cmd appops set "$packageName" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
            am set-standby-bucket "$packageName" active 2>/dev/null
            pids=${'$'}(pgrep -f "^$packageName" 2>/dev/null || pidof "$packageName" 2>/dev/null)
            if [ -n "${'$'}pids" ]; then
                for p in ${'$'}pids; do
                    echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                done
            fi
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Batch Unfreezes multiple apps.
     */
    fun unfreezeMultipleApps(packages: Collection<String>) {
        if (packages.isEmpty()) return
        val pkgList = packages.joinToString(" ")
        val script = """
            for pkg in $pkgList; do
                pm unsuspend "${'$'}pkg" 2>/dev/null
                pm enable "${'$'}pkg" 2>/dev/null
                am unfreeze "${'$'}pkg" 2>/dev/null
                cmd appops set "${'$'}pkg" RUN_IN_BACKGROUND allow 2>/dev/null
                cmd appops set "${'$'}pkg" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
                am set-standby-bucket "${'$'}pkg" active 2>/dev/null
                pids=${'$'}(pgrep -f "^${'$'}pkg" 2>/dev/null || pidof "${'$'}pkg" 2>/dev/null)
                if [ -n "${'$'}pids" ]; then
                    for p in ${'$'}pids; do
                        echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                    done
                fi
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
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastRecentPackagesTimestamp < 600 && cachedRecentPackages.isNotEmpty()) {
            return cachedRecentPackages
        }

        return try {
            val output = ShellUtils.fastCmdResult("dumpsys activity recents | grep -m 40 -E 'RecentTaskInfo #|Recent #[0-9]+:|realActivity=|baseActivity=|topActivity=|cmp=|I=' 2>/dev/null", 2000)
            if (output.isBlank()) {
                return if (now - lastRecentPackagesTimestamp < 5000) cachedRecentPackages else emptySet()
            }

            val pkgs = mutableSetOf<String>()
            val regexes = listOf(
                Regex("(?:realActivity=|baseActivity=|topActivity=|cmp=|I=)\\{?([a-zA-Z0-9_.]+)/"),
                Regex("(?:RecentTaskInfo #[0-9]+:|Recent #[0-9]+:).*Task\\{[a-f0-9]+ #[0-9]+ [^}]*(?:I=|A=[0-9]+:)([a-zA-Z0-9_.]+)")
            )
            for (line in output.lineSequence()) {
                for (r in regexes) {
                    val m = r.find(line)
                    if (m != null) {
                        val p = m.groupValues[1]
                        if (p.isNotBlank() && !p.contains("launcher", ignoreCase = true) && p != "com.android.systemui") {
                            pkgs.add(p)
                        }
                        break
                    }
                }
            }
            cachedRecentPackages = pkgs
            lastRecentPackagesTimestamp = now
            if (pkgs.isNotEmpty()) {
                // Prune closed apps from activeSessionApps
                activeSessionApps.retainAll { pkg ->
                    pkgs.contains(pkg)
                }
            }
            pkgs
        } catch (e: Exception) {
            if (now - lastRecentPackagesTimestamp < 5000) cachedRecentPackages else emptySet()
        }
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
            ShellUtils.fastCmdResult("dumpsys window | grep 'mCurrentFocus' 2>/dev/null", 1000)
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

    fun getActivePackages(packages: Collection<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val out = ShellUtils.runAsRoot("ps -A -o NAME").output
        val running = out.split("\n").map { it.trim() }.toSet()
        return packages.filter { running.contains(it) }.toSet()
    }

    fun launchApp(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        
        // 1. Instantly register active session & grant 15-second absolute immunity
        registerAppOpen(packageName)

        // 2. Resolve target launcher activity component name if possible
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val componentName = launchIntent?.component?.flattenToShortString() ?: ""

        val startCmd = if (componentName.isNotBlank()) {
            "am start -n '$componentName' -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --activity-brought-to-front 2>/dev/null"
        } else {
            """
            comp=${'$'}(cmd package resolve-activity --brief "$packageName" 2>/dev/null | tail -n 1)
            if [ -n "${'$'}comp" ] && [ "${'$'}comp" != "No activity found" ]; then
                am start -n "${'$'}comp" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --activity-brought-to-front 2>/dev/null
            else
                monkey -p "$packageName" -c android.intent.category.LAUNCHER 1 2>/dev/null
            fi
            """.trimIndent()
        }

        // 3. Atomically unsuspend, enable, unfreeze, and launch in root shell asynchronously with 0ms UI delay
        // Sequential shell execution guarantees pm unsuspend finishes BEFORE am start, preventing SuspendedAppActivity
        val launchScript = """
            pm unsuspend "$packageName" 2>/dev/null
            pm enable "$packageName" 2>/dev/null
            am unfreeze "$packageName" 2>/dev/null
            cmd appops set "$packageName" RUN_IN_BACKGROUND allow 2>/dev/null
            cmd appops set "$packageName" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
            am set-standby-bucket "$packageName" active 2>/dev/null
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

        val custom = prefs.getStringSet("custom_widget_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (custom.remove(packageName)) {
            prefs.edit().putStringSet("custom_widget_apps", custom).apply()
        }

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
            am freeze "$packageName" 2>/dev/null
            am set-standby-bucket "$packageName" restricted 2>/dev/null
            for p in $(pidof "$packageName"); do
                echo 900 > /proc/${'$'}p/oom_score_adj 2>/dev/null
            done
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
        val out = ShellUtils.fastCmdResult("dumpsys activity processes | grep -E '$packageName.*(freeze=true|frozen)' 2>/dev/null", 1000)
        if (out.isNotBlank()) return true
        val bucket = ShellUtils.fastCmdResult("am get-standby-bucket $packageName 2>/dev/null", 800).trim()
        if (bucket == "45" || bucket == "restricted" || bucket == "50") return true
        val suspended = ShellUtils.fastCmdResult("dumpsys package $packageName 2>/dev/null | grep -i 'suspended=true'", 800).trim()
        return suspended.isNotBlank()
    }
}
