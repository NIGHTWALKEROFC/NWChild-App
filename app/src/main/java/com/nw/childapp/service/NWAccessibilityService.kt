// PATH: nw-child-app/app/src/main/java/com/nw/childapp/service/NWAccessibilityService.kt
package com.nw.childapp.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat

/**
 * Monitors foreground app changes.
 * - Blocks apps listed in shared prefs "blocked_apps"
 * - Enforces daily limits from "app_limits"
 * - Requires user to enable in Settings → Accessibility
 */
class NWAccessibilityService : AccessibilityService() {

    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Post a low-priority sticky notification so Android keeps this service alive
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel("nw_acc", "NW Child Monitor", NotificationManager.IMPORTANCE_MIN)
        nm.createNotificationChannel(ch)
        startForeground(3, buildNotification())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == this.packageName) return      // never block ourselves

        checkBlockedApp(pkg)
        checkAppLimit(pkg)
    }

    override fun onInterrupt() { /* required override */ }

    // ── Block check ──────────────────────────────────────────────────

    private fun checkBlockedApp(pkg: String) {
        val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: return
        if (blocked.contains(pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            notify("App Blocked", "$pkg is blocked by your parent.", pkg.hashCode())
        }
    }

    // ── Usage limit check ────────────────────────────────────────────

    private fun checkAppLimit(pkg: String) {
        val limitsJson = prefs.getString("app_limits", "{}") ?: return
        val limit = parseLimitForPackage(limitsJson, pkg) ?: return
        val usedMinutes = prefs.getInt("used_$pkg", 0)
        if (usedMinutes >= limit) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            notify("Limit Reached", "Daily limit for $pkg has been reached.", (pkg + "_limit").hashCode())
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun parseLimitForPackage(json: String, pkg: String): Int? {
        // Simple key extraction: "com.example.app":60
        val key   = "\"$pkg\""
        val start = json.indexOf(key)
        if (start == -1) return null
        val colon = json.indexOf(':', start + key.length)
        if (colon == -1) return null
        val end = json.indexOfFirst { it == ',' || it == '}' }.takeIf { it > colon }
            ?: json.length
        return json.substring(colon + 1, end).trim().toIntOrNull()
    }

    private fun notify(title: String, text: String, id: Int) {
        val nm        = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "nw_child_block"
        nm.createNotificationChannel(
            NotificationChannel(channelId, "App Control", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.notify(id,
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "nw_acc")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NW Child Active")
            .setContentText("Device protection running")
            .setOngoing(true)
            .build()
    }
}