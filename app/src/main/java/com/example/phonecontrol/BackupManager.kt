package com.example.phonecontrol

import android.content.Context
import org.json.JSONObject

object BackupManager {

    private val PREF_FILES = listOf("prefs", "freezer_prefs", "multitasking_prefs", "firewall_prefs")

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
