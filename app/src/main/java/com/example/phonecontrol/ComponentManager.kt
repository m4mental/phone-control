package com.example.phonecontrol

import android.content.pm.PackageManager
import android.util.Log

object ComponentManager {

    /**
     * Enables or disables a specific component of an app.
     * @param packageName Package name of the app.
     * @param componentName Full class name of the component (Service/Receiver).
     * @param enabled True to enable, False to disable.
     */
    fun setComponentState(packageName: String, componentName: String, enabled: Boolean) {
        val state = if (enabled) "enable" else "disable"
        // pm enable/disable <package>/<component>
        ShellUtils.runAsRoot("pm $state $packageName/$componentName")
    }

    /**
     * Fetches all services and receivers for a given package.
     */
    fun getAppComponents(pm: PackageManager, packageName: String): List<ComponentInfo> {
        return try {
            val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_DISABLED_COMPONENTS)
            val list = mutableListOf<ComponentInfo>()

            packageInfo.services?.forEach { 
                list.add(ComponentInfo(it.name, "Service", pm.getComponentEnabledSetting(android.content.ComponentName(packageName, it.name)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
            }
            packageInfo.receivers?.forEach { 
                list.add(ComponentInfo(it.name, "Receiver", pm.getComponentEnabledSetting(android.content.ComponentName(packageName, it.name)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
            }
            list.sortedBy { it.name }
        } catch (e: Exception) {
            Log.e("ComponentManager", "Error fetching components", e)
            emptyList()
        }
    }

    data class ComponentInfo(val name: String, val type: String, var isEnabled: Boolean)
}
