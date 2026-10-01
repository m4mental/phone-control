package com.example.phonecontrol

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BackupManager {

    private const val TAG = "BackupManager"
    const val FORMAT_VERSION = 2

    val PREF_FILES = listOf(
        "prefs",
        "freezer_prefs",
        "multitasking_prefs",
        "firewall_prefs",
        "vault_prefs",
        "game_turbo_prefs",
        "super_doze_prefs",
        "tower_prefs",
        "per_app_prefs",
        "update_shield_prefs",
        "studio_equalizer_prefs",
        "per_app_eq_prefs",
        "poweramp_presets"
    )

    private val KNOWN_NAMESPACES = PREF_FILES.toSet()

    private const val ROOT_DIR = "/sdcard/PHONE_CONTROL"
    private const val CONFIG_DIR = "$ROOT_DIR/Config_Backups"
    private const val VAULT_DIR = "$ROOT_DIR/App_Vault"

    private data class TypedPref(val type: String, val value: Any)

    /**
     * Ensures the folder structure exists on internal storage using root.
     */
    fun ensureStorageStructure() {
        ShellUtils.runAsRoot("mkdir -p $CONFIG_DIR")
        ShellUtils.runAsRoot("mkdir -p $VAULT_DIR")
    }

    fun getAutoConfigPath(): String = CONFIG_DIR
    fun getAutoVaultPath(): String = VAULT_DIR

    /**
     * Automatically saves a backup to the PHONE_CONTROL/Config_Backups folder.
     */
    fun saveBackupAuto(context: Context): Boolean {
        ensureStorageStructure()
        val json = generateBackupJson(context) ?: return false
        val fileName = "Config_Backup_${System.currentTimeMillis()}.json"
        val destPath = "$CONFIG_DIR/$fileName"
        if (!ShellUtils.validatePathInBaseDir(destPath, CONFIG_DIR)) return false

        val tempFile = File(context.cacheDir, fileName)
        try {
            tempFile.writeText(json)
            val qTemp = ShellUtils.shellQuote(tempFile.absolutePath)
            val qDest = ShellUtils.shellQuote(destPath)
            val result = ShellUtils.runAsRoot("cp $qTemp $qDest")
            return result.exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save auto backup: ${e.message}", e)
            return false
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Restores the latest backup found in the Config_Backups folder.
     */
    fun restoreLatestAuto(context: Context): Boolean {
        val result = ShellUtils.runAsRoot("ls -t $CONFIG_DIR/*.json 2>/dev/null | head -n 1")
        if (result.exitCode != 0 || result.output.isBlank()) return false

        val latestFile = result.output.trim()
        if (!ShellUtils.validatePathInBaseDir(latestFile, CONFIG_DIR) || !latestFile.endsWith(".json")) {
            return false
        }
        val catRes = ShellUtils.runAsRoot("cat " + ShellUtils.shellQuote(latestFile))
        if (catRes.exitCode != 0 || catRes.output.isBlank()) return false
        return restoreFromJson(context, catRes.output)
    }

    /**
     * Generates a typed JSON string containing all relevant SharedPreferences.
     * Original types (Boolean, Int, Long, Float, String, Set<String>) are explicitly tagged.
     */
    fun generateBackupJson(context: Context): String? {
        return try {
            val masterJson = JSONObject()
            masterJson.put("format_version", FORMAT_VERSION)
            masterJson.put("created_at", System.currentTimeMillis())

            val namespacesJson = JSONObject()
            for (prefName in PREF_FILES) {
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                val allEntries = prefs.all
                if (allEntries.isEmpty()) continue

                val prefJson = JSONObject()
                for ((key, value) in allEntries) {
                    if (value == null) continue
                    val entryObj = JSONObject()
                    when (value) {
                        is Boolean -> {
                            entryObj.put("type", "boolean")
                            entryObj.put("value", value)
                        }
                        is Int -> {
                            entryObj.put("type", "int")
                            entryObj.put("value", value)
                        }
                        is Long -> {
                            entryObj.put("type", "long")
                            entryObj.put("value", value)
                        }
                        is Float -> {
                            entryObj.put("type", "float")
                            entryObj.put("value", value.toDouble())
                        }
                        is String -> {
                            entryObj.put("type", "string")
                            entryObj.put("value", value)
                        }
                        is Set<*> -> {
                            entryObj.put("type", "string_set")
                            val array = JSONArray()
                            for (item in value) {
                                if (item != null) array.put(item.toString())
                            }
                            entryObj.put("value", array)
                        }
                        else -> continue
                    }
                    prefJson.put(key, entryObj)
                }
                if (prefJson.length() > 0) {
                    namespacesJson.put(prefName, prefJson)
                }
            }
            masterJson.put("namespaces", namespacesJson)
            masterJson.toString(4)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating backup JSON: ${e.message}", e)
            null
        }
    }

    /**
     * Restores SharedPreferences from a JSON string.
     * All-or-nothing: parses and validates the entire document before clearing any preferences.
     * Only accepts known namespaces. Preserves original Float and Long types.
     * Rejects empty documents and does not start the daemon on failure.
     */
    fun restoreFromJson(context: Context, jsonString: String): Boolean {
        if (jsonString.isBlank()) {
            Log.w(TAG, "Restore rejected: empty input document")
            return false
        }

        return try {
            val masterJson = JSONObject(jsonString)
            val namespacesObj = if (masterJson.has("namespaces")) {
                masterJson.optJSONObject("namespaces") ?: return false
            } else {
                masterJson
            }

            val staged = mutableMapOf<String, MutableMap<String, TypedPref>>()
            val nsKeys = namespacesObj.keys()
            var totalEntryCount = 0

            while (nsKeys.hasNext()) {
                val ns = nsKeys.next()
                if (ns == "format_version" || ns == "created_at" || ns == "version") {
                    continue
                }
                if (!KNOWN_NAMESPACES.contains(ns)) {
                    Log.w(TAG, "Restore rejected: unknown namespace '$ns'")
                    return false
                }

                val nsObj = namespacesObj.optJSONObject(ns) ?: return false
                val entriesMap = mutableMapOf<String, TypedPref>()
                val entryKeys = nsObj.keys()

                while (entryKeys.hasNext()) {
                    val key = entryKeys.next()
                    val rawVal = nsObj.get(key)
                    val typedPref = if (rawVal is JSONObject && rawVal.has("type") && rawVal.has("value")) {
                        when (rawVal.getString("type")) {
                            "boolean" -> TypedPref("boolean", rawVal.getBoolean("value"))
                            "int" -> TypedPref("int", rawVal.getInt("value"))
                            "long" -> TypedPref("long", rawVal.getLong("value"))
                            "float" -> TypedPref("float", rawVal.getDouble("value").toFloat())
                            "string" -> TypedPref("string", rawVal.getString("value"))
                            "string_set" -> {
                                val arr = rawVal.getJSONArray("value")
                                val set = mutableSetOf<String>()
                                for (i in 0 until arr.length()) {
                                    set.add(arr.getString(i))
                                }
                                TypedPref("string_set", set)
                            }
                            else -> {
                                Log.w(TAG, "Restore rejected: invalid type tag '${rawVal.optString("type")}' for key '$key'")
                                return false
                            }
                        }
                    } else {
                        // Backward-compatible fallback for legacy untagged backup documents
                        when (rawVal) {
                            is Boolean -> TypedPref("boolean", rawVal)
                            is Int -> TypedPref("int", rawVal)
                            is Long -> TypedPref("long", rawVal)
                            is Double -> TypedPref("float", rawVal.toFloat())
                            is String -> TypedPref("string", rawVal)
                            is JSONArray -> {
                                val set = mutableSetOf<String>()
                                for (i in 0 until rawVal.length()) {
                                    set.add(rawVal.getString(i))
                                }
                                TypedPref("string_set", set)
                            }
                            else -> {
                                Log.w(TAG, "Restore rejected: unsupported raw type for key '$key'")
                                return false
                            }
                        }
                    }
                    entriesMap[key] = typedPref
                    totalEntryCount++
                }

                if (entriesMap.isNotEmpty()) {
                    staged[ns] = entriesMap
                }
            }

            // Reject empty documents
            if (staged.isEmpty() || totalEntryCount == 0) {
                Log.w(TAG, "Restore rejected: document contains no valid preference entries")
                return false
            }

            // Document is valid: perform all-or-nothing preference overwrite
            for ((prefName, entries) in staged) {
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.clear()
                for ((key, typed) in entries) {
                    when (typed.type) {
                        "boolean" -> editor.putBoolean(key, typed.value as Boolean)
                        "int" -> editor.putInt(key, typed.value as Int)
                        "long" -> editor.putLong(key, typed.value as Long)
                        "float" -> editor.putFloat(key, typed.value as Float)
                        "string" -> editor.putString(key, typed.value as String)
                        "string_set" -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, typed.value as Set<String>)
                    }
                }
                editor.apply()
            }

            // Restart daemon and auto tweak service to apply restored configuration only on success
            DaemonManager.startDaemon(context)
            Log.d(TAG, "Configuration restored successfully ($totalEntryCount entries in ${staged.size} namespaces)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore configuration from JSON: ${e.message}", e)
            false
        }
    }

    /**
     * Checks if the latest backup in Config_Backups is older than 7 days (or none exists).
     * If so, automatically generates a new timestamped backup asynchronously without blocking.
     */
    fun checkAndRunScheduledAutoBackup(context: Context) {
        kotlin.concurrent.thread {
            try {
                ensureStorageStructure()
                val dir = File(CONFIG_DIR)
                val backupFiles = dir.listFiles { file -> file.name.startsWith("Config_Backup_") && file.name.endsWith(".json") }
                val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
                val now = System.currentTimeMillis()

                val shouldBackup = if (backupFiles.isNullOrEmpty()) {
                    true
                } else {
                    val latestTime = backupFiles.maxOfOrNull { it.lastModified() } ?: 0L
                    (now - latestTime) > sevenDaysMs
                }

                if (shouldBackup) {
                    saveBackupAuto(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scheduled auto backup failed: ${e.message}", e)
            }
        }
    }
}
