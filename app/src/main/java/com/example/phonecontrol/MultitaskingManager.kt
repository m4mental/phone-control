package com.example.phonecontrol

object MultitaskingManager {
    /**
     * List of apps that are prioritized and protected from freezing during multitasking.
     * Includes Messaging, Social Media, and Browsers.
     */
    val protectedApps = listOf(
        "com.whatsapp",
        "com.instagram.android",
        "com.facebook.katana",
        "com.snapchat.android",
        "com.android.chrome",
        "com.brave.browser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.google.android.youtube"
    )

    fun isProtected(packageName: String, context: android.content.Context? = null): Boolean {
        if (protectedApps.contains(packageName)) return true
        
        if (context != null) {
            val userWhitelist = getUserWhitelist(context)
            if (userWhitelist.contains(packageName)) return true
        }
        return false
    }

    fun getUserWhitelist(context: android.content.Context): Set<String> {
        val prefs = context.getSharedPreferences("multitasking_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getStringSet("user_whitelist", emptySet()) ?: emptySet()
    }

    fun saveUserWhitelist(context: android.content.Context, whitelist: Set<String>) {
        val prefs = context.getSharedPreferences("multitasking_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putStringSet("user_whitelist", whitelist).apply()
    }
}
