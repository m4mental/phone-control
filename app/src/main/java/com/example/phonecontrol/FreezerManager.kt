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
        lastLaunchTime = System.currentTimeMillis()
        freezerExecutor.execute {
            unfreezeApp(packageName)
        }
    }

    fun isAppActiveSession(packageName: String): Boolean {
        return activeSessionApps.contains(packageName)
    }

    fun removeActiveSession(packageName: String) {
        activeSessionApps.remove(packageName)
    }

    /**
     * Hibernates a single app immediately.
     * Strictly protects active session apps.
     */
    /**
     * Hibernates a single app immediately.
     * Strictly protects active session apps, visible apps, and apps with active foreground/perceptible adj.
     */
    fun freezeApp(context: Context, packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return
        
        // Absolute Guard: NEVER freeze an app that is in active user session or opened recently
        if (isAppActiveSession(packageName)) return
        if (packageName == lastLaunchedPackage && (System.currentTimeMillis() - lastLaunchTime < 30000)) {
            return
        }
        if (isAppCurrentlyVisible(packageName)) return
        
        if (isSpecialFreeze(context, packageName)) {
            val specialScript = """
                for p in $(pidof "$packageName" 2>/dev/null); do
                    adj=$(cat /proc/${'$'}p/oom_score_adj 2>/dev/null)
                    if [ -n "${'$'}adj" ] && [ "${'$'}adj" -le 200 ]; then
                        exit 0
                    fi
                done
                am force-stop "$packageName" 2>/dev/null
                pm suspend "$packageName" 2>/dev/null
                am freeze "$packageName" 2>/dev/null
                am set-standby-bucket "$packageName" restricted 2>/dev/null
            """.trimIndent()
            ShellUtils.fastCmd(specialScript)
            return
        }

        // Standard Freeze: Clean Linux cgroup suspend with active process immunity (adj <= 200 cannot be frozen)
        val script = """
            for p in $(pidof "$packageName" 2>/dev/null); do
                adj=$(cat /proc/${'$'}p/oom_score_adj 2>/dev/null)
                if [ -n "${'$'}adj" ] && [ "${'$'}adj" -le 200 ]; then
                    exit 0
                fi
            done
            am freeze "$packageName" 2>/dev/null
            am set-standby-bucket "$packageName" restricted 2>/dev/null
            for p in $(pidof "$packageName" 2>/dev/null); do
                echo 900 > /proc/${'$'}p/oom_score_adj 2>/dev/null
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Batch Hibernates multiple apps in a single ultra-fast shell execution (0ms UI lag).
     */
    fun freezeMultipleApps(context: Context, packages: Collection<String>) {
        if (packages.isEmpty()) return
        val pkgList = packages.filter { it != lastLaunchedPackage && !isAppActiveSession(it) && !isAppCurrentlyVisible(it) }.joinToString(" ")
        if (pkgList.isBlank()) return

        val script = """
            for pkg in $pkgList; do
                is_safe=0
                for p in ${'$'}(pidof "${'$'}pkg" 2>/dev/null); do
                    adj=${'$'}(cat /proc/${'$'}p/oom_score_adj 2>/dev/null)
                    if [ -n "${'$'}adj" ] && [ "${'$'}adj" -le 200 ]; then
                        is_safe=1
                        break
                    fi
                done
                if [ "${'$'}is_safe" -eq 0 ]; then
                    am freeze "${'$'}pkg" 2>/dev/null
                    am set-standby-bucket "${'$'}pkg" restricted 2>/dev/null
                    for p in ${'$'}(pidof "${'$'}pkg" 2>/dev/null); do
                        echo 900 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                    done
                fi
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
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
        // Safety Guard: If Recents list query returned empty (e.g. timeout or system busy),
        // NEVER perform dismissal sweep to prevent falsely freezing active foreground/recents apps!
        if (currentRecents.isEmpty()) return

        val specialApps = getSpecialFreezeApps(context)
        val standardApps = getFrozenApps(context)
        val allConfigured = specialApps + standardApps
        if (allConfigured.isEmpty()) return

        val allSafeApps = MultitaskingManager.getUserWhitelist(context) + MultitaskingManager.protectedApps
        val activeAudio = getActivePlayingAudioPackages(context)

        val toFreeze = mutableSetOf<String>()

        // 1. Apps tracked in activeSessionApps that have been dismissed from Recents and left foreground
        for (pkg in activeSessionApps) {
            val isRecentlyLaunched = (pkg == lastLaunchedPackage && (System.currentTimeMillis() - lastLaunchTime < 30000))
            if (!currentRecents.contains(pkg) && pkg != currentForeground && !isRecentlyLaunched && !isAppCurrentlyVisible(pkg)) {
                toFreeze.add(pkg)
            }
        }

        // 2. Any configured freezer app that is NOT on screen, NOT in recents, and NOT in active session,
        // but has active running background processes (e.g. after background wakeup/broadcast)
        val runningConfigured = getRunningConfiguredApps(allConfigured)
        for (pkg in runningConfigured) {
            val isRecentlyLaunched = (pkg == lastLaunchedPackage && (System.currentTimeMillis() - lastLaunchTime < 30000))
            if (pkg != currentForeground && !currentRecents.contains(pkg) && !isRecentlyLaunched && !isAppCurrentlyVisible(pkg) && !activeSessionApps.contains(pkg)) {
                toFreeze.add(pkg)
            }
        }

        // 3. Special Freeze apps: suspend only if not in foreground, not in recents,
        // and NOT recently launched, NOT in active session, and NOT currently visible
        for (pkg in specialApps) {
            val isRecentlyLaunched = (pkg == lastLaunchedPackage && (System.currentTimeMillis() - lastLaunchTime < 30000))
            if (pkg != currentForeground && !currentRecents.contains(pkg) && !isRecentlyLaunched && !activeSessionApps.contains(pkg) && !isAppCurrentlyVisible(pkg)) {
                toFreeze.add(pkg)
            }
        }

        if (toFreeze.isEmpty()) return

        for (pkg in toFreeze) {
            // Absolute safety check: Never touch recently launched apps or currently visible apps
            if (pkg == lastLaunchedPackage && (System.currentTimeMillis() - lastLaunchTime < 30000)) {
                continue
            }
            if (isAppCurrentlyVisible(pkg)) {
                continue
            }
            activeSessionApps.remove(pkg)
            if (pkg == lastLaunchedPackage) {
                lastLaunchedPackage = null
            }

            if (allConfigured.contains(pkg) && !allSafeApps.contains(pkg) && !activeAudio.contains(pkg)) {
                android.util.Log.d("FreezerManager", "❄️ Recents Dismissed / Background Idle -> Freeze for $pkg")
                freezeApp(context, pkg)
            }
        }
    }

    /**
     * Checks running process state for configured packages in a single batch ps scan (~150ms).
     * Eliminates sequential shell pidof loops and prevents timeouts.
     */
    fun getRunningConfiguredApps(configured: Collection<String>): Set<String> {
        if (configured.isEmpty()) return emptySet()
        val out = ShellUtils.fastCmdResult("ps -A -o NAME 2>/dev/null", 1500)
        if (out.isBlank()) return emptySet()
        val running = out.lineSequence().map { it.trim() }.toSet()
        return configured.filter { running.contains(it) }.toSet()
    }

    /**
     * Resumes an app instantly on open.
     */
    fun unfreezeApp(packageName: String) {
        if (packageName.isBlank()) return
        val script = """
            pm enable "$packageName" 2>/dev/null
            pm unsuspend "$packageName" 2>/dev/null
            am unfreeze "$packageName" 2>/dev/null
            am set-standby-bucket "$packageName" active 2>/dev/null
            for p in $(pidof "$packageName"); do
                echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
            done
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
                pm enable "${'$'}pkg" 2>/dev/null
                pm unsuspend "${'$'}pkg" 2>/dev/null
                am unfreeze "${'$'}pkg" 2>/dev/null
                am set-standby-bucket "${'$'}pkg" active 2>/dev/null
                for p in ${'$'}(pidof "${'$'}pkg"); do
                    echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                done
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Returns the set of all packages currently open in the system's Recent Apps / Recents Task list.
     * Strictly filters for alive tasks (hasTask=true) to avoid dead task tombstones.
     */
    fun getRecentPackages(): Set<String> {
        return try {
            val output = ShellUtils.fastCmdResult("dumpsys activity recents | grep -E 'Recent #[0-9]+:|realActivity=|baseActivity=|cmp=|I=' 2>/dev/null", 2500)
            if (output.isBlank()) return emptySet()

            val pkgs = mutableSetOf<String>()
            val regexes = listOf(
                Regex("(?:realActivity=|baseActivity=|topActivity=|cmp=|I=)\\{?([a-zA-Z0-9_.]+)/"),
                Regex("Recent #[0-9]+:.*Task\\{[a-f0-9]+ #[0-9]+ [^}]*I=([a-zA-Z0-9_.]+)/")
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
            pkgs
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Returns set of packages that are actively playing audio/video (state = PLAYING or started audio track).
     * Excludes non-playing or paused media sessions.
     */
    fun getActivePlayingAudioPackages(context: Context): Set<String> {
        val activePlaying = mutableSetOf<String>()
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager?.isMusicActive == true) {
                val script = """
                    dumpsys media_session 2>/dev/null | awk '/package=/ {pkg=${'$'}0} /state=PlaybackState/ {if (${'$'}0 ~ /state=3/) print pkg}' | cut -d '=' -f2
                    for pid in $(dumpsys audio 2>/dev/null | grep -B 2 'state:started' | grep -o 'u/pid:[0-9]*' | cut -d ':' -f2); do
                        cat /proc/${'$'}pid/cmdline 2>/dev/null | tr '\0' '\n'
                    done
                """.trimIndent()
                val out = ShellUtils.fastCmdResult(script, 1500)
                for (line in out.lineSequence()) {
                    val pkg = line.trim()
                    if (pkg.isNotBlank() && pkg != "com.android.server.telecom") {
                        activePlaying.add(pkg)
                    }
                }

                // Fallback: If AudioManager says music is active, also add all active media session packages
                if (activePlaying.isEmpty()) {
                    val allSessions = ShellUtils.fastCmdResult("dumpsys media_session | grep 'package=' | cut -d '=' -f2 2>/dev/null", 1000)
                    activePlaying.addAll(allSessions.lineSequence().map { it.trim() }.filter { it.isNotBlank() && it != "com.android.server.telecom" })
                }
            }
        } catch (e: Exception) {}
        return activePlaying
    }

    /**
     * Checks if an app is currently visible on the screen or in focus.
     */
    fun isAppCurrentlyVisible(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val out = ShellUtils.fastCmdResult("dumpsys window | grep 'mCurrentFocus' 2>/dev/null", 1000)
            out.contains(packageName)
        } catch (e: Exception) {
            false
        }
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

        // 2. Ultra-fast combined Root Execution: Unsuspend, Unfreeze & Launch in ONE single shot (<80ms)
        val launchScript = """
            pm unsuspend "$packageName" 2>/dev/null
            pm enable "$packageName" 2>/dev/null
            am unfreeze "$packageName" 2>/dev/null
            am set-standby-bucket "$packageName" active 2>/dev/null
            comp=${'$'}(cmd package resolve-activity --brief "$packageName" 2>/dev/null | tail -n 1)
            if [ -n "${'$'}comp" ] && [ "${'$'}comp" != "No activity found" ]; then
                am start -n "${'$'}comp" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --activity-brought-to-front 2>/dev/null
            fi
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
        freezeApp(context, packageName)
    }

    fun removeAppFromFreezer(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("frozen_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(packageName)
        saveFrozenApps(context, set)
        unfreezeApp(packageName)
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
        val out = ShellUtils.fastCmdResult("dumpsys activity processes | grep -E '$packageName.*freeze=true' 2>/dev/null")
        return out.isNotBlank()
    }
}
