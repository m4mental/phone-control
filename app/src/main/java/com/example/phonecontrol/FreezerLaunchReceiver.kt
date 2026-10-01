package com.example.phonecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Non-exported BroadcastReceiver that safely handles widget launch events.
 * Validates package names and ensures membership in the configured freezer selection lists
 * before delegating to FreezerManager.launchApp.
 */
class FreezerLaunchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra("package_name")
            ?: return

        // 1. Validate package name format without repairing input
        if (!ShellUtils.isValidPackageName(packageName)) {
            Log.w(TAG, "Rejected launch request: invalid package name format '$packageName'")
            return
        }

        // 2. Validate membership in stored widget or special-freeze selection list
        val standardApps = FreezerManager.getFrozenApps(context)
        val specialApps = FreezerManager.getSpecialFreezeApps(context)
        val customWidgetApps = FreezerManager.getCustomWidgetApps(context)
        val allowedApps = standardApps + specialApps + customWidgetApps

        if (!allowedApps.contains(packageName)) {
            Log.w(TAG, "Rejected launch request: package '$packageName' not in stored widget or freezer selection list")
            return
        }

        // 3. Launch application safely and schedule widget refresh
        kotlin.concurrent.thread {
            FreezerManager.launchApp(context, packageName)
            Handler(Looper.getMainLooper()).postDelayed({
                FreezerWidgetProvider.updateAllWidgets(context)
                SpecialFreezerWidgetProvider.updateAllWidgets(context)
            }, 500)
        }
    }

    companion object {
        private const val TAG = "FreezerLaunchReceiver"
        const val ACTION_LAUNCH_APP = "com.example.phonecontrol.ACTION_LAUNCH_APP"
        const val EXTRA_PACKAGE_NAME = "com.example.phonecontrol.EXTRA_PACKAGE_NAME"
    }
}
