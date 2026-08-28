package com.example.phonecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.*
import android.os.Build
import android.telephony.NetworkRegistrationInfo
import android.telephony.TelephonyDisplayInfo
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class XposedInit : IXposedHookLoadPackage {

    private val CHANNEL_ID = "nr_monitor_xposed"
    private val NOTIF_ID = 999
    
    private var lastLabel = ""
    private var lastColor = -1

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // We only care about the Phone process where network logic lives
        if (lpparam.packageName != "com.android.phone") return

        XposedBridge.log("PhoneControl: Hooking into Phone Process...")

        try {
            // Hook ServiceStateTracker.updateSpnDisplay (common entry for signal/icon updates)
            XposedHelpers.findAndHookMethod(
                "com.android.internal.telephony.ServiceStateTracker",
                lpparam.classLoader,
                "updateSpnDisplay",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        update5GStatus(param.thisObject)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("PhoneControl Hook Error: $e")
        }
    }

    private fun update5GStatus(sst: Any) {
        try {
            val context = XposedHelpers.getObjectField(sst, "mPhone")?.let { 
                XposedHelpers.callMethod(it, "getContext") as? Context 
            } ?: return

            // Extract NR State directly from the object fields
            val serviceState = XposedHelpers.getObjectField(sst, "mSS") ?: return
            
            // Get raw nrState via reflection on ServiceState
            val nrState = try {
                val networkRegistrationInfo = XposedHelpers.callMethod(serviceState, "getNetworkRegistrationInfo", 1, 1) // DOMAIN_PS, WWAN
                XposedHelpers.callMethod(networkRegistrationInfo, "getNrState") as Int
            } catch (e: Exception) { 0 }

            // Get Display Info (What the OS shows in status bar)
            val displayInfo = XposedHelpers.getObjectField(sst, "mDisplayInfo") as? TelephonyDisplayInfo
            val ovrType = displayInfo?.overrideNetworkType ?: 0

            // Decision Logic
            val isReal5G = nrState == 3 // CONNECTED
            val isFake5G = !isReal5G && (ovrType >= 3) // NSA Icon present but no NR leg

            val (label, text, color) = when {
                isReal5G -> Triple("5G", "✅ Real 5G Active", Color.GREEN)
                isFake5G -> Triple("5G!", "⚠️ Fake 5G (Anchor)", Color.YELLOW)
                else -> Triple("4G", "📶 4G LTE", Color.RED)
            }

            if (label != lastLabel || color != lastColor) {
                lastLabel = label
                lastColor = color
                showNotification(context, label, text, color)
            }
        } catch (e: Throwable) {
            XposedBridge.log("PhoneControl Update Error: $e")
        }
    }

    private fun showNotification(context: Context, label: String, content: String, color: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "5G Monitor", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val iconBitmap = createTextBitmap(label, color)
        
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setContentTitle("5G Data Guard")
            .setContentText(content)
            .setSmallIcon(android.graphics.drawable.Icon.createWithBitmap(iconBitmap))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(color)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        manager.notify(NOTIF_ID, builder.build())
    }

    private fun createTextBitmap(text: String, color: Int): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = size * 0.75f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val xPos = canvas.width / 2f
        val yPos = (canvas.height / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(text, xPos, yPos, paint)
        return bitmap
    }
}
