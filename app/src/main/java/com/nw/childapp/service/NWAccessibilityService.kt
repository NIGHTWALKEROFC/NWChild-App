// PATH: app/src/main/java/com/nw/childapp/service/NWAccessibilityService.kt
package com.nw.childapp.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import android.util.Log

/**
 * Accessibility Service for app blocking and usage limits.
 * Detects foreground app changes and enforces parent rules.
 *
 * HOW TO ENABLE (no restricted settings needed):
 * Go to Settings → Accessibility → NW Child Monitor → Enable
 * On newer Android: Settings → Apps → Special app access →
 * Accessibility → NW Child Monitor → ON
 */
class NWAccessibilityService : AccessibilityService() {

    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }
    private var currentPackage = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("NWAccessibility", "Service connected")

        // Configure what events to receive
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info

        startForeground(3, buildNotification())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == this.packageName) return
        if (pkg == currentPackage) return  // same app, skip
        currentPackage = pkg

        checkAndEnforce(pkg)
    }

    private fun checkAndEnforce(pkg: String) {
        // Check blocked apps
        val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
        if (blocked.contains(pkg)) {
            Log.d("NWAccessibility", "Blocking app: $pkg")
            performGlobalAction(GLOBAL_ACTION_HOME)
            showNotification("App Blocked", "$pkg is blocked by your parent.")
            return
        }

        // Check time limits — stored as limit_{packageName} = minutes
        val limitMins = prefs.getInt("limit_$pkg", -1)
        if (limitMins > 0) {
            val usedMins = prefs.getInt("used_$pkg", 0)
            if (usedMins >= limitMins) {
                Log.d("NWAccessibility", "Limit reached: $pkg used=$usedMins limit=$limitMins")
                performGlobalAction(GLOBAL_ACTION_HOME)
                showNotification("Daily Limit Reached", "You've used your daily limit for $pkg.")
            }
        }
    }

    private fun showNotification(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "nw_block"
        nm.createNotificationChannel(
            NotificationChannel(channelId, "App Control", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.notify(title.hashCode(),
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        )
    }

    override fun onInterrupt() {}

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("nw_acc", "NW Child Active", NotificationManager.IMPORTANCE_MIN)
        )
        return NotificationCompat.Builder(this, "nw_acc")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NW Child Active")
            .setContentText("Device protection running")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}