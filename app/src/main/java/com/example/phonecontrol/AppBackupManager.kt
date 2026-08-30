package com.example.phonecontrol

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AppBackupManager {

    const val DEFAULT_VAULT_PATH = "/sdcard/PHONE_CONTROL/App_Vault"

    /**
     * Data class to hold backup information.
     */
    data class BackupInfo(
        val packageName: String,
        val appName: String,
        val version: String,
        val versionCode: Long,
        val date: String,
        val notes: String,
        val hasData: Boolean,
        val hasApk: Boolean,
        val hasObb: Boolean = false
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("package_name", packageName)
            json.put("app_name", appName)
            json.put("version", version)
            json.put("version_code", versionCode)
            json.put("date", date)
            json.put("notes", notes)
            json.put("has_data", hasData)
            json.put("has_apk", hasApk)
            json.put("has_obb", hasObb)
            return json.toString(4)
        }
    }

    /**
     * Extracts App Info (Version, Name, etc.)
     */
    fun getAppInfo(context: Context, packageName: String): BackupInfo? {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(packageName, 0)
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appName = pm.getApplicationLabel(appInfo).toString()
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            
            BackupInfo(
                packageName,
                appName,
                info.versionName ?: "Unknown",
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
                date,
                "",
                hasData = true,
                hasApk = true
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Core Backup Engine: Runs in background via Service.
     */
    fun performBackup(
        context: Context,
        info: BackupInfo,
        masterPath: String,
        includeApk: Boolean,
        includeData: Boolean,
        includeObb: Boolean,
        onProgress: (Int, String) -> Unit
    ): Boolean {
        try {
            val backupDir = "$masterPath/${info.packageName}_${System.currentTimeMillis()}"
            ShellUtils.runAsRoot("mkdir -p $backupDir")

            // 1. APK Backup
            if (includeApk) {
                onProgress(15, "Extracting base APK...")
                val apkPath = ShellUtils.runAsRoot("pm path ${info.packageName}").output
                    .split("\n")
                    .firstOrNull { it.startsWith("package:") }
                    ?.substringAfter("package:")
                
                if (apkPath != null) {
                    ShellUtils.runAsRoot("cp $apkPath $backupDir/base.apk")
                }
            }

            // 2. Data Backup (tar.gz)
            if (includeData) {
                onProgress(45, "Flushing and archiving app data...")
                ShellUtils.runAsRoot("am force-stop ${info.packageName} && sync")
                val dataPath = "/data/data/${info.packageName}"
                val dataOutput = "$backupDir/data.tar.gz"
                
                val tarCmd = "tar -czf $dataOutput -C $dataPath . --exclude='cache' --exclude='code_cache'"
                val tarResult = ShellUtils.runAsRoot(tarCmd)
                
                if (tarResult.exitCode != 0) {
                    onProgress(55, "Data compression fallback...")
                    ShellUtils.runAsRoot("cd $dataPath && tar -czf $dataOutput .")
                }
            }

            // 3. OBB Backup
            if (includeObb) {
                onProgress(75, "Compressing OBB game data...")
                val obbPath = "/sdcard/Android/obb/${info.packageName}"
                val obbOutput = "$backupDir/obb.tar.gz"
                
                if (ShellUtils.runAsRoot("ls -d $obbPath").exitCode == 0) {
                    ShellUtils.runAsRoot("tar -czf $obbOutput -C $obbPath .")
                }
            }

            // 4. Metadata
            onProgress(90, "Saving metadata...")
            val finalInfo = info.copy(hasApk = includeApk, hasData = includeData, hasObb = includeObb)
            val metadataFile = File(context.cacheDir, "metadata.json")
            metadataFile.writeText(finalInfo.toJson())
            ShellUtils.runAsRoot("cp ${metadataFile.absolutePath} $backupDir/info.json")
            metadataFile.delete()

            // Final permissions
            ShellUtils.runAsRoot("chmod -R 777 $backupDir")
            
            onProgress(100, "Backup Complete!")
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Restore Engine: Granularly restores APK, Data, and/or OBB based on user selection.
     */
    fun performRestore(
        context: Context,
        backupPath: String,
        restoreApk: Boolean = true,
        restoreData: Boolean = true,
        restoreObb: Boolean = true,
        onProgress: (Int, String) -> Unit
    ): Boolean {
        try {
            val infoFile = ShellUtils.runAsRoot("cat $backupPath/info.json").output
            val infoJson = JSONObject(infoFile)
            val pkg = infoJson.getString("package_name")
            val hasApk = infoJson.optBoolean("has_apk", true)
            val hasData = infoJson.optBoolean("has_data", true)
            val hasObb = infoJson.optBoolean("has_obb", false)

            // 1. Install APK if selected & available
            if (restoreApk && hasApk && ShellUtils.runAsRoot("ls $backupPath/base.apk").exitCode == 0) {
                onProgress(20, "Installing APK...")
                ShellUtils.runAsRoot("pm install -r $backupPath/base.apk")
            }

            // 2. Restore Data if selected & available
            if (restoreData && hasData && ShellUtils.runAsRoot("ls $backupPath/data.tar.gz").exitCode == 0) {
                onProgress(50, "Restoring app data folders...")
                ShellUtils.runAsRoot("am force-stop $pkg")
                
                val dataPath = "/data/data/$pkg"
                if (ShellUtils.runAsRoot("ls -d $dataPath").exitCode == 0) {
                    ShellUtils.runAsRoot("find $dataPath -mindepth 1 -delete")
                    ShellUtils.runAsRoot("tar -xzf $backupPath/data.tar.gz -C $dataPath")

                    // Fix UID Permissions & SELinux Context
                    onProgress(70, "Fixing data permissions & SELinux...")
                    try {
                        val uid = context.packageManager.getApplicationInfo(pkg, 0).uid
                        ShellUtils.runAsRoot("chown -R $uid:$uid $dataPath")
                        ShellUtils.runAsRoot("restorecon -R $dataPath")
                    } catch (e: Exception) {}
                }
            }

            // 3. Restore OBB if selected & available
            if (restoreObb && hasObb && ShellUtils.runAsRoot("ls $backupPath/obb.tar.gz").exitCode == 0) {
                onProgress(85, "Restoring OBB media data...")
                val obbPath = "/sdcard/Android/obb/$pkg"
                ShellUtils.runAsRoot("mkdir -p $obbPath")
                ShellUtils.runAsRoot("find $obbPath -mindepth 1 -delete")
                ShellUtils.runAsRoot("tar -xzf $backupPath/obb.tar.gz -C $obbPath")
            }

            onProgress(100, "Restore Complete!")
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
