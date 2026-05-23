// PATH: nw-child-app/app/src/main/java/com/nw/childapp/service/NWNotificationListenerService.kt
package com.nw.childapp.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.firebase.database.FirebaseDatabase

/**
 * Notification Listener Service.
 * Captures incoming notification metadata and forwards it to Firebase
 * so the parent can monitor notification activity on the child device.
 * Only title and package name are sent — no message body for privacy.
 * Requires user to enable in Settings → Notification access.
 */
class NWNotificationListenerService : NotificationListenerService() {

    private val db    by lazy { FirebaseDatabase.getInstance() }
    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == this.packageName) return   // skip our own notifications

        val deviceId = prefs.getString("device_id", null) ?: return

        val data = mapOf(
            "packageName" to sbn.packageName,
            "title"       to (sbn.notification.extras.getString("android.title") ?: ""),
            "timestamp"   to sbn.postTime,
            "deviceId"    to deviceId
        )

        db.getReference("notifications")
            .child(deviceId)
            .child(sbn.postTime.toString())
            .setValue(data)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: track when notifications are dismissed
    }
}