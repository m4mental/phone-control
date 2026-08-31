package com.example.phonecontrol

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

class SpecialFreezerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val intent = Intent(context, SpecialFreezerWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(this.toUri(Intent.URI_INTENT_SCHEME))
            }

            val views = RemoteViews(context.packageName, R.layout.widget_special_freezer).apply {
                setRemoteAdapter(R.id.widget_grid, intent)
                setEmptyView(R.id.widget_grid, R.id.widget_empty_view)
            }

            // Click template to launch apps
            val launchIntent = Intent(context, SpecialFreezerWidgetProvider::class.java).apply {
                action = ACTION_LAUNCH_SPECIAL_APP
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 101, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_grid, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_LAUNCH_SPECIAL_APP) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            packageName?.let {
                kotlin.concurrent.thread {
                    FreezerManager.launchApp(context, it)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        updateAllWidgets(context)
                        FreezerWidgetProvider.updateAllWidgets(context)
                    }, 500)
                }
            }
        }
    }

    companion object {
        const val ACTION_LAUNCH_SPECIAL_APP = "com.example.phonecontrol.ACTION_LAUNCH_SPECIAL_APP"
        const val EXTRA_PACKAGE_NAME = "com.example.phonecontrol.EXTRA_PACKAGE_NAME"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, SpecialFreezerWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName)
            appWidgetManager.notifyAppWidgetViewDataChanged(ids, R.id.widget_grid)
        }
    }
}
