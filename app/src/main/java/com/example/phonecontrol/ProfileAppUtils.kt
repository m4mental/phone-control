package com.example.phonecontrol

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserManager

data class ProfileApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val userId: Int,
    val uid: Int,
    val category: AppCategory,
    val isSystem: Boolean = false
) {
    val uniqueKey: String get() = if (userId == 0) packageName else "$packageName:$userId"
}

object ProfileAppUtils {

    fun getInstalledAppsAcrossProfiles(context: Context): List<ProfileApp> {
        val pm = context.packageManager
        val list = mutableListOf<ProfileApp>()
        val seenKeys = mutableSetOf<String>()

        // 1. Android official LauncherApps API across all UserHandles (Profiles)
        try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val profiles = userManager?.userProfiles ?: emptyList()

            if (launcherApps != null && profiles.isNotEmpty()) {
                for (userHandle in profiles) {
                    val uidRegex = Regex("""\d+""").find(userHandle.toString())?.value ?: "0"
                    val uId = uidRegex.toIntOrNull() ?: 0
                    val cat = AppCategory.fromUserId(uId)

                    try {
                        val activities = launcherApps.getActivityList(null, userHandle)
                        for (act in activities) {
                            val pkg = act.applicationInfo.packageName
                            if (pkg == context.packageName) continue
                            val key = if (uId == 0) pkg else "$pkg:$uId"
                            if (!seenKeys.contains(key)) {
                                seenKeys.add(key)
                                val isSys = (act.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                                list.add(
                                    ProfileApp(
                                        packageName = pkg,
                                        label = act.label.toString(),
                                        icon = act.getBadgedIcon(0),
                                        userId = uId,
                                        uid = act.applicationInfo.uid,
                                        category = cat,
                                        isSystem = isSys
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        // 2. Root Shell fallback to inspect User 0, 999 (Dual), 10+ (Private Space) non-launcher apps
        try {
            val usersOut = ShellUtils.runAsRoot("pm list users").output
            val userIds = mutableSetOf(0)
            Regex("""UserInfo\{(\d+):""").findAll(usersOut).forEach { match ->
                match.groupValues[1].toIntOrNull()?.let { userIds.add(it) }
            }

            for (uId in userIds) {
                val cat = AppCategory.fromUserId(uId)
                val pkgsOut = ShellUtils.runAsRoot("pm list packages -3 -U --user $uId").output
                val lines = pkgsOut.lines()
                for (line in lines) {
                    if (!line.startsWith("package:")) continue
                    val parts = line.removePrefix("package:").trim().split(" ")
                    val pkgName = parts[0]
                    if (pkgName == context.packageName) continue
                    val key = if (uId == 0) pkgName else "$pkgName:$uId"
                    if (seenKeys.contains(key)) continue

                    val uidVal = parts.find { it.startsWith("uid:") }?.removePrefix("uid:")?.toIntOrNull()
                        ?: (uId * 100000 + (try { pm.getApplicationInfo(pkgName, 0).uid % 100000 } catch (_: Exception) { 10000 }))

                    seenKeys.add(key)
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
                    } catch (_: Exception) {
                        pkgName.substringAfterLast(".")
                    }
                    val icon = try {
                        pm.getApplicationIcon(pkgName)
                    } catch (_: Exception) {
                        null
                    }
                    list.add(
                        ProfileApp(
                            packageName = pkgName,
                            label = label,
                            icon = icon,
                            userId = uId,
                            uid = uidVal,
                            category = cat,
                            isSystem = false
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback to normal pm if list is empty
        if (list.isEmpty()) {
            try {
                val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in all) {
                    if (app.packageName == context.packageName) continue
                    if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(app.packageName) != null) {
                        val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        list.add(
                            ProfileApp(
                                packageName = app.packageName,
                                label = pm.getApplicationLabel(app).toString(),
                                icon = pm.getApplicationIcon(app),
                                userId = 0,
                                uid = app.uid,
                                category = AppCategory.MAIN,
                                isSystem = isSys
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        return list.sortedWith(compareBy({ it.category.ordinal }, { it.label.lowercase() }))
    }
}
