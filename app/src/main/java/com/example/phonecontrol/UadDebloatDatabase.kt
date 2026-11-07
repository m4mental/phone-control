package com.example.phonecontrol

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

enum class DebloatLevel(val label: String, val colorHex: String) {
    RECOMMENDED("Safe (Recommended)", "#00E676"),
    ADVANCED("Advanced (Optional)", "#FFAB40"),
    EXPERT("Expert (Caution)", "#FF5252")
}

data class UadPackage(
    val packageName: String,
    val name: String,
    val brand: String, // "NOTHING", "GOOGLE", "XIAOMI", "SAMSUNG", "BBK", "CARRIER", "AOSP"
    val level: DebloatLevel,
    val description: String
)

object UadDebloatDatabase {

    private const val PREFS_NAME = "uad_debloat_prefs"
    private const val KEY_UNDO_HISTORY = "uad_undo_history"

    val CORE_PROTECTED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.providers.media",
        "com.android.providers.downloads",
        "com.nothing.launcher",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
        "com.android.server.telecom",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.keychain",
        "com.android.carrierconfig",
        "com.android.vending",
        "com.example.phonecontrol"
    )

    // Curated UAD-NG (Universal Android Debloater Next Generation) Database
    val UAD_DATABASE = listOf(
        // === NOTHING OS BLOATWARE ===
        UadPackage("com.nothing.community", "Nothing Community", "NOTHING", DebloatLevel.RECOMMENDED, "Official forum and community app"),
        UadPackage("com.nothing.feedback", "Nothing Feedback Tool", "NOTHING", DebloatLevel.RECOMMENDED, "Diagnostic crash and bug reporter"),
        UadPackage("com.nothing.weather", "Nothing Weather", "NOTHING", DebloatLevel.ADVANCED, "Default weather app and widget"),
        UadPackage("com.nothing.voice", "Nothing Voice Recorder", "NOTHING", DebloatLevel.ADVANCED, "Built-in voice recorder app"),
        UadPackage("com.nothing.glyph", "Nothing Glyph Diagnostics", "NOTHING", DebloatLevel.ADVANCED, "Internal glyph debug/test services"),

        // === GOOGLE ECOSYSTEM SAFE BLOAT ===
        UadPackage("com.google.android.videos", "Google TV / Movies", "GOOGLE", DebloatLevel.RECOMMENDED, "Movie and video rental storefront"),
        UadPackage("com.google.android.apps.youtube.music", "YouTube Music", "GOOGLE", DebloatLevel.RECOMMENDED, "Preinstalled streaming music player"),
        UadPackage("com.google.android.apps.tachyon", "Google Meet / Duo", "GOOGLE", DebloatLevel.RECOMMENDED, "Preinstalled video calling service"),
        UadPackage("com.google.android.apps.podcasts", "Google Podcasts", "GOOGLE", DebloatLevel.RECOMMENDED, "Deprecated podcast service"),
        UadPackage("com.google.android.feedback", "Market Feedback Agent", "GOOGLE", DebloatLevel.RECOMMENDED, "Sends crash reports and telemetry to Google"),
        UadPackage("com.google.android.printservice.recommendation", "Print Service Recommendation", "GOOGLE", DebloatLevel.RECOMMENDED, "Background plugin scanner for network printers"),
        UadPackage("com.android.bips", "Default Print Service", "GOOGLE", DebloatLevel.RECOMMENDED, "Built-in background Mopria print daemon"),
        UadPackage("com.google.android.marvin.talkback", "TalkBack Accessibility", "GOOGLE", DebloatLevel.RECOMMENDED, "Screen reader service for vision-impaired users"),
        UadPackage("com.google.android.projection.gearhead", "Android Auto", "GOOGLE", DebloatLevel.ADVANCED, "Automotive vehicle dashboard connection"),
        UadPackage("com.google.android.apps.subscriptions.red", "Google One", "GOOGLE", DebloatLevel.RECOMMENDED, "Subscription management and cloud backup promo"),
        UadPackage("com.google.android.apps.fitness", "Google Fit", "GOOGLE", DebloatLevel.RECOMMENDED, "Background fitness and pedometer tracking"),
        UadPackage("com.google.ar.core", "Google Play Services for AR", "GOOGLE", DebloatLevel.ADVANCED, "Augmented reality camera measurement engine"),
        UadPackage("com.google.android.apps.wellbeing", "Digital Wellbeing", "GOOGLE", DebloatLevel.ADVANCED, "App usage timer and screen time telemetry"),
        UadPackage("com.google.android.apps.docs", "Google Drive", "GOOGLE", DebloatLevel.ADVANCED, "Cloud document storage app"),
        UadPackage("com.google.android.apps.photos", "Google Photos", "GOOGLE", DebloatLevel.ADVANCED, "Cloud photo backup and gallery"),

        // === XIAOMI / MIUI / HYPEROS BLOATWARE ===
        UadPackage("com.miui.analytics", "MIUI Analytics", "XIAOMI", DebloatLevel.RECOMMENDED, "Xiaomi system tracking and user telemetry"),
        UadPackage("com.miui.msa.global", "MIUI System Ads (MSA)", "XIAOMI", DebloatLevel.RECOMMENDED, "Displays advertisement banners in system apps"),
        UadPackage("com.miui.bugreport", "MIUI Bug Report", "XIAOMI", DebloatLevel.RECOMMENDED, "Diagnostic and crash report collector"),
        UadPackage("com.miui.yellowpage", "Xiaomi Yellow Pages", "XIAOMI", DebloatLevel.RECOMMENDED, "Caller identification and spam directory service"),
        UadPackage("com.xiaomi.joyose", "Joyose Analytics & Throttle", "XIAOMI", DebloatLevel.RECOMMENDED, "FPS limiter and background telemetry collector"),
        UadPackage("com.miui.cloudservice", "Xiaomi Cloud", "XIAOMI", DebloatLevel.RECOMMENDED, "Xiaomi cloud sync and storage daemon"),
        UadPackage("com.miui.cloudbackup", "Xiaomi Cloud Backup", "XIAOMI", DebloatLevel.RECOMMENDED, "Auto-backup to Xiaomi Chinese servers"),
        UadPackage("com.mi.globalbrowser", "Mi Browser", "XIAOMI", DebloatLevel.RECOMMENDED, "Bloated web browser with ad trackers"),
        UadPackage("com.miui.videoplayer", "Mi Video", "XIAOMI", DebloatLevel.RECOMMENDED, "Online streaming player with sponsored content"),
        UadPackage("com.miui.player", "Mi Music", "XIAOMI", DebloatLevel.RECOMMENDED, "Music player with push notifications and ads"),
        UadPackage("com.miui.hybrid", "Quick Apps (Hybrid)", "XIAOMI", DebloatLevel.RECOMMENDED, "Web app runner heavily used for ads"),
        UadPackage("com.miui.cleanmaster", "Clean Master Junk Cleaner", "XIAOMI", DebloatLevel.RECOMMENDED, "Cheetah Mobile analytics and cleaner module"),
        UadPackage("com.xiaomi.midrop", "ShareMe / Mi Drop", "XIAOMI", DebloatLevel.ADVANCED, "Xiaomi local file transfer utility"),
        UadPackage("com.miui.notes", "Xiaomi Notes", "XIAOMI", DebloatLevel.ADVANCED, "Default note taking application"),

        // === SAMSUNG ONEUI BLOATWARE ===
        UadPackage("com.samsung.android.bixby.agent", "Bixby Voice Assistant", "SAMSUNG", DebloatLevel.RECOMMENDED, "Samsung voice assistant and wakeword listener"),
        UadPackage("com.samsung.android.bixby.service", "Bixby Service", "SAMSUNG", DebloatLevel.RECOMMENDED, "Background processing for Bixby assistant"),
        UadPackage("com.samsung.android.app.spage", "Samsung Free / Daily", "SAMSUNG", DebloatLevel.RECOMMENDED, "Leftmost homescreen news and partner card feed"),
        UadPackage("com.samsung.android.aremoji", "AR Emoji", "SAMSUNG", DebloatLevel.RECOMMENDED, "Animated 3D avatar generator and stickers"),
        UadPackage("com.samsung.android.kidsinstaller", "Samsung Kids", "SAMSUNG", DebloatLevel.RECOMMENDED, "Parental sandbox mode downloader"),
        UadPackage("com.samsung.android.game.gamehome", "Samsung Gaming Hub", "SAMSUNG", DebloatLevel.RECOMMENDED, "Game launcher with promotional advertisements"),
        UadPackage("com.samsung.android.game.gos", "Game Optimizing Service (GOS)", "SAMSUNG", DebloatLevel.ADVANCED, "App and game resolution/FPS thermal throttler"),
        UadPackage("com.samsung.android.mdx", "Samsung Link to Windows", "SAMSUNG", DebloatLevel.ADVANCED, "Microsoft Phone Link integration daemon"),
        UadPackage("com.sec.android.app.sbrowser", "Samsung Internet", "SAMSUNG", DebloatLevel.ADVANCED, "Samsung web browser"),
        UadPackage("com.samsung.android.rubin.app", "Samsung Customization Service", "SAMSUNG", DebloatLevel.RECOMMENDED, "Personalized ad tracker and behavior profiler"),
        UadPackage("com.samsung.android.authfw", "Samsung Pass / Wallet", "SAMSUNG", DebloatLevel.ADVANCED, "Biometric authentication manager"),

        // === BBK (ONEPLUS / OPPO / REALME / VIVO) ===
        UadPackage("com.heytap.cloud", "HeyTap Cloud", "BBK", DebloatLevel.RECOMMENDED, "Oppo/Realme cloud backup and sync"),
        UadPackage("com.heytap.market", "HeyTap App Market", "BBK", DebloatLevel.RECOMMENDED, "Secondary store with spam notifications"),
        UadPackage("com.heytap.browser", "HeyTap Browser", "BBK", DebloatLevel.RECOMMENDED, "Preinstalled web browser with tracking"),
        UadPackage("com.oplus.logkit", "OPlus LogKit", "BBK", DebloatLevel.RECOMMENDED, "Extensive diagnostic and telemetry logger"),
        UadPackage("com.coloros.gamespace", "ColorOS Game Space", "BBK", DebloatLevel.ADVANCED, "In-game overlay with telemetry"),
        UadPackage("com.oplus.cosa", "Quick Device Connect", "BBK", DebloatLevel.RECOMMENDED, "Background bluetooth/wifi device scanner"),
        UadPackage("com.oplus.crashbox", "OPlus CrashBox", "BBK", DebloatLevel.RECOMMENDED, "Crash dumps and telemetry sender"),

        // === CARRIER / META / MICROSOFT PARTNER BLOAT ===
        UadPackage("com.facebook.katana", "Facebook", "CARRIER", DebloatLevel.RECOMMENDED, "Main Facebook client app"),
        UadPackage("com.facebook.system", "Meta App Installer", "CARRIER", DebloatLevel.RECOMMENDED, "System service for background Facebook silent updates"),
        UadPackage("com.facebook.appmanager", "Meta App Manager", "CARRIER", DebloatLevel.RECOMMENDED, "Telemetry and silent installer for Meta services"),
        UadPackage("com.facebook.services", "Meta Services", "CARRIER", DebloatLevel.RECOMMENDED, "Continuous background synchronization for Meta"),
        UadPackage("com.netflix.partner.activation", "Netflix Partner Activation", "CARRIER", DebloatLevel.RECOMMENDED, "Pre-activation stub for Netflix billing"),
        UadPackage("com.netflix.mediaclient", "Netflix", "CARRIER", DebloatLevel.RECOMMENDED, "Preinstalled Netflix streaming app"),
        UadPackage("com.microsoft.skydrive", "Microsoft OneDrive", "CARRIER", DebloatLevel.RECOMMENDED, "Preloaded Microsoft cloud backup stub")
    )

    fun getDetectedBrand(): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("nothing") -> "NOTHING"
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "XIAOMI"
            m.contains("samsung") -> "SAMSUNG"
            m.contains("oneplus") || m.contains("oppo") || m.contains("realme") || m.contains("vivo") -> "BBK"
            m.contains("google") -> "GOOGLE"
            else -> "GOOGLE"
        }
    }

    fun getPackagesForBrand(brand: String): List<UadPackage> {
        return when (brand) {
            "ALL" -> UAD_DATABASE
            "DETECTED" -> {
                val detected = getDetectedBrand()
                UAD_DATABASE.filter { it.brand == detected || it.brand == "GOOGLE" || it.brand == "CARRIER" }
            }
            else -> UAD_DATABASE.filter { it.brand.equals(brand, ignoreCase = true) }
        }
    }

    // --- Undo History Tracking ---

    data class UndoItem(
        val packageName: String,
        val appName: String,
        val timestamp: Long
    )

    fun recordDebloated(context: Context, packageName: String, appName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_UNDO_HISTORY, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            // Remove existing if any
            val cleanList = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("pkg") != packageName) {
                    cleanList.add(obj)
                }
            }
            // Prepend latest
            val newObj = JSONObject().apply {
                put("pkg", packageName)
                put("name", appName)
                put("time", System.currentTimeMillis())
            }
            val newArr = JSONArray()
            newArr.put(newObj)
            for (obj in cleanList) newArr.put(obj)
            prefs.edit().putString(KEY_UNDO_HISTORY, newArr.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getUndoHistory(context: Context): List<UndoItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_UNDO_HISTORY, "[]") ?: "[]"
        val list = mutableListOf<UndoItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    UndoItem(
                        packageName = obj.getString("pkg"),
                        appName = obj.optString("name", obj.getString("pkg")),
                        timestamp = obj.optLong("time", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun removeUndo(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_UNDO_HISTORY, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("pkg") != packageName) {
                    newArr.put(obj)
                }
            }
            prefs.edit().putString(KEY_UNDO_HISTORY, newArr.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearAllUndo(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_UNDO_HISTORY).apply()
    }
}
