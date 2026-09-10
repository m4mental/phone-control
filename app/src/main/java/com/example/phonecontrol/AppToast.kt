package com.example.phonecontrol

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Smart, Non-Intrusive Toast Manager.
 * Cancels any currently visible Toast before displaying a new one to prevent
 * annoying multi-toast queues when buttons or toggles are clicked repeatedly.
 * Also debounces identical messages within 1.5s to keep the screen clean.
 */
object AppToast {

    private var currentToast: Toast? = null
    private var lastMessage: String? = null
    private var lastToastTime: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context?, message: String?, duration: Int = Toast.LENGTH_SHORT) {
        if (context == null || message.isNullOrBlank()) return

        val now = System.currentTimeMillis()
        if (message == lastMessage && (now - lastToastTime) < 1500L) {
            // Debounce duplicate message in quick succession
            return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            displayToast(context, message, duration, now)
        } else {
            mainHandler.post {
                displayToast(context, message, duration, now)
            }
        }
    }

    private fun displayToast(context: Context, message: String, duration: Int, timestamp: Long) {
        try {
            currentToast?.cancel()
            lastMessage = message
            lastToastTime = timestamp

            currentToast = Toast.makeText(context.applicationContext, message, duration)
            currentToast?.show()
        } catch (e: Exception) {
            // Silently handle context invalidation
        }
    }
}
