package com.example.phonecontrol

import android.content.Context
import org.json.JSONObject
import java.io.File

object BackupManager {

    private val PREF_FILES = listOf("prefs", "freezer_prefs", "multitasking_prefs", "firewall_prefs", "vault_prefs")
    private const val ROOT_DIR = "/sdcard/PHONE_CONTROL"
    private const val CONFIG_DIR = "$ROOT_DIR/Config_Backups"
    private const val VAULT_DIR = "$ROOT_DIR/App_Vault"

    /**
     * Ensures the folder structure exists on internal storage using root.
     */
    fun ensureStorageStructure() {
        ShellUtils.runAsRoot("mkdir -p $CONFIG_DIR")
        ShellUtils.runAsRoot("mkdir -p $VAULT_DIR")
        ShellUtils.runAsRoot("chmod -R 777 $ROOT_DIR")
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
        val tempFile = File(context.cacheDir, fileName)
        tempFile.writeText(json)
        
        val result = ShellUtils.runAsRoot("cp ${tempFile.absolutePath} $CONFIG_DIR/$fileName && chmod 666 $CONFIG_DIR/$fileName")
        tempFile.delete()
        return result.exitCode == 0
    }

    /**
     * Restores the latest backup found in the Config_Backups folder.
     */
    fun restoreLatestAuto(context: Context): Boolean {
        val result = ShellUtils.runAsRoot("ls -t $CONFIG_DIR/*.json | head -n 1")
        if (result.exitCode != 0 || result.output.isBlank()) return false
        
        val latestFile = result.output.trim()
        val fileContent = ShellUtils.runAsRoot("cat $latestFile").output
        return restoreFromJson(context, fileContent)
    }

    /**
     * Generates a JSON string containing all relevant SharedPreferences.
     */
    fun generateBackupJson(context: Context): String? {
        return try {
            val masterJson = JSONObject()
            for (prefName in PREF_FILES) {
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                val allEntries = prefs.all
                val prefJson = JSONObject()
                for ((key, value) in allEntries) {
                    if (value is Set<*>) {
                        val array = org.json.JSONArray()
                        value.forEach { array.put(it) }
                        prefJson.put(key, array)
                    } else {
                        prefJson.put(key, value)
                    }
                }
                masterJson.put(prefName, prefJson)
            }
            masterJson.toString(4)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Restores SharedPreferences from a JSON string.
     */
    fun restoreFromJson(context: Context, jsonString: String): Boolean {
        return try {
            if (jsonString.isBlank()) return false
            
            val masterJson = JSONObject(jsonString)
            for (prefName in PREF_FILES) {
                if (!masterJson.has(prefName)) continue
                
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.clear()
                
                val prefJson = masterJson.getJSONObject(prefName)
                val keys = prefJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = prefJson.get(key)
                    
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is String -> editor.putString(key, value)
                        is org.json.JSONArray -> {
                            val set = mutableSetOf<String>()
                            for (i in 0 until value.length()) {
                                set.add(value.getString(i))
                            }
                            editor.putStringSet(key, set)
                        }
                    }
                }
                editor.apply()
            }
            // Restart daemon to pick up new settings
            DaemonManager.startDaemon(context)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
