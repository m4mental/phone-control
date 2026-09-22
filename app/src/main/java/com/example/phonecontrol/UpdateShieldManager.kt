package com.example.phonecontrol

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * High-performance Play Store Detach & Update Shield Manager.
 * Decouples selected applications from Google Play Store's (com.android.vending) internal
 * SQLite appstate and auto_update tables so Play Store stops showing updates or triggering
 * background auto-updates for them.
 */
object UpdateShieldManager {

    private const val TAG = "UpdateShieldManager"
    private const val PREFS_NAME = "update_shield_prefs"
    private const val KEY_SHIELDED_PACKAGES = "shielded_packages"
    private const val VENDING_DIR = "/data/data/com.android.vending"
    private const val VENDING_DB_DIR = "$VENDING_DIR/databases"
    private const val LOCAL_APP_STATE_DB = "$VENDING_DB_DIR/localappstate.db"
    private const val LIBRARY_DB = "$VENDING_DB_DIR/library.db"

    fun isMasterEnabled(context: Context): Boolean {
        val masterPrefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val hubEnabled = masterPrefs.getBoolean("master_tools_hub_enabled", true)
        val shieldEnabled = masterPrefs.getBoolean("update_shield_enabled", true)
        return hubEnabled && shieldEnabled
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        val masterPrefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val editor = masterPrefs.edit()
        if (enabled) {
            editor.putBoolean("master_tools_hub_enabled", true)
        }
        editor.putBoolean("update_shield_enabled", enabled).commit()
        if (enabled) {
            enforceAllShields(context)
        } else {
            // When master setting is turned OFF, stop enforcement and clean Play Store memory
            ShellUtils.runAsRootMm("am force-stop com.android.vending")
        }
    }

    fun getShieldedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SHIELDED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun isShielded(context: Context, packageName: String): Boolean {
        return getShieldedPackages(context).contains(packageName)
    }

    fun setShielded(context: Context, packageName: String, shielded: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_SHIELDED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()

        if (shielded) {
            current.add(packageName)
            // Ensure Master Settings toggle is ON if user shields an app
            val masterPrefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            if (!masterPrefs.getBoolean("update_shield_enabled", true) || !masterPrefs.getBoolean("master_tools_hub_enabled", true)) {
                masterPrefs.edit()
                    .putBoolean("master_tools_hub_enabled", true)
                    .putBoolean("update_shield_enabled", true)
                    .commit()
            }
        } else {
            current.remove(packageName)
        }
        prefs.edit().putStringSet(KEY_SHIELDED_PACKAGES, current).commit()

        return if (shielded) {
            detachPackages(context, listOf(packageName))
        } else {
            reattachPackage(packageName)
        }
    }

    fun detachPackages(context: Context, packageNames: List<String>): Boolean {
        if (packageNames.isEmpty()) return true
        return try {
            val checkRes = ShellUtils.runAsRootMm("[ -f $LOCAL_APP_STATE_DB ] && echo 'EXISTS'").output
            if (!checkRes.contains("EXISTS")) {
                Log.w(TAG, "Play Store localappstate.db not found at $LOCAL_APP_STATE_DB: $checkRes")
            }

            val vendingUid = ShellUtils.runAsRootMm("stat -c '%u' $VENDING_DIR").output.trim().ifBlank { "10168" }

            val cacheDir = File(context.cacheDir, "update_shield")
            cacheDir.mkdirs()

            // 1. Purge localappstate.db (main app registration & state)
            purgeDatabase(cacheDir, "localappstate.db", vendingUid) { db ->
                for (pkg in packageNames) {
                    try { db.execSQL("DELETE FROM appstate WHERE package_name = ?", arrayOf(pkg)) } catch (e: Exception) {}
                    try { db.execSQL("DELETE FROM auto_update WHERE package_name = ?", arrayOf(pkg)) } catch (e: Exception) {}
                }
            }

            // 2. Purge auto_update.db (standalone auto update database)
            purgeDatabase(cacheDir, "auto_update.db", vendingUid) { db ->
                val cursor = try { db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null) } catch (e: Exception) { null }
                val tables = mutableListOf<String>()
                cursor?.use {
                    while (it.moveToNext()) {
                        tables.add(it.getString(0))
                    }
                }
                for (pkg in packageNames) {
                    for (tbl in tables) {
                        try { db.execSQL("DELETE FROM $tbl WHERE package_name = ?", arrayOf(pkg)) } catch (e: Exception) {}
                        try { db.execSQL("DELETE FROM $tbl WHERE doc_id = ?", arrayOf(pkg)) } catch (e: Exception) {}
                    }
                }
            }

            // 3. Purge library.db (library ownership records)
            purgeDatabase(cacheDir, "library.db", vendingUid) { db ->
                for (pkg in packageNames) {
                    try { db.execSQL("DELETE FROM ownership WHERE doc_id = ?", arrayOf(pkg)) } catch (e: Exception) {}
                    try { db.execSQL("DELETE FROM auto_update WHERE package_name = ?", arrayOf(pkg)) } catch (e: Exception) {}
                }
            }

            // 4. Force-stop Play Store so it drops memory cache and re-syncs state
            ShellUtils.runAsRootMm("am force-stop com.android.vending")
            true
        } catch (e: Exception) {
            Log.e(TAG, "detachPackages failed", e)
            false
        }
    }

    private fun purgeDatabase(cacheDir: File, dbName: String, vendingUid: String, operation: (SQLiteDatabase) -> Unit) {
        try {
            val dbPath = "$VENDING_DB_DIR/$dbName"
            val existsRes = ShellUtils.runAsRootMm("[ -f $dbPath ] && echo 'EXISTS'").output
            if (!existsRes.contains("EXISTS")) return

            val stagedDb = File(cacheDir, dbName)
            ShellUtils.runAsRootMm("cp $dbPath* ${cacheDir.absolutePath}/ && chmod 666 ${stagedDb.absolutePath}*")

            if (stagedDb.exists()) {
                try {
                    val db = SQLiteDatabase.openDatabase(stagedDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                    operation(db)
                    db.close()

                    ShellUtils.runAsRootMm(
                        "cp ${stagedDb.absolutePath}* $VENDING_DB_DIR/ && " +
                        "chown -R $vendingUid:$vendingUid $VENDING_DB_DIR/ && " +
                        "chmod 660 $dbPath*"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error purging database $dbName: ${e.message}")
                } finally {
                    try { stagedDb.delete() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "purgeDatabase error for $dbName", e)
        }
    }

    fun reattachPackage(packageName: String): Boolean {
        ShellUtils.runAsRootMm("am force-stop com.android.vending")
        return true
    }

    fun enforceAllShields(context: Context) {
        if (!isMasterEnabled(context)) {
            Log.d(TAG, "Update Shield is disabled in Master Settings. Skipping enforcement.")
            return
        }
        val shielded = getShieldedPackages(context)
        if (shielded.isNotEmpty()) {
            detachPackages(context, shielded.toList())
        }
    }
}
