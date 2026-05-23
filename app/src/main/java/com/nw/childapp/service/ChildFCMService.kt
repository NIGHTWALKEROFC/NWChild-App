// PATH: nw-child-app/app/src/main/java/com/nw/childapp/service/ChildFCMService.kt
package com.nw.childapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nw.childapp.ui.MainActivity

// ─────────────────────────────────────────────────────────────────────
//  FCM Service — receives push notifications from Firebase Cloud Messaging
// ─────────────────────────────────────────────────────────────────────
class ChildFCMService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "NW Child"
        val body  = message.notification?.body  ?: message.data["body"]  ?: ""
        showNotification(title, body)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs    = getSharedPreferences("child_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return
        prefs.edit().putString("fcm_token", token).apply()
        // Update stored FCM token in Firebase
        FirebaseDatabase.getInstance()
            .getReference("devices")
            .child(deviceId)
            .child("fcmToken")
            .setValue(token)
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "nw_child_fcm"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "NW Child Alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        nm.notify(System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }
}

// ─────────────────────────────────────────────────────────────────────
//  Boot Receiver — restarts ChildMonitorService after device reboot
// ─────────────────────────────────────────────────────────────────────
class ChildBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs    = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
        val isPaired = prefs.getBoolean("is_paired", false)
        if (isPaired) {
            context.startForegroundService(Intent(context, ChildMonitorService::class.java))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
//  Device Admin Receiver — prevents unauthorised uninstall
//  User must explicitly disable Device Admin in Settings before
//  the system will allow the app to be uninstalled.
// ─────────────────────────────────────────────────────────────────────
class NWDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device admin active — uninstall protection is now on
    }

    /** Warning message shown when user tries to deactivate admin from Settings */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Disabling device admin will remove NW Child protection. " +
        "Please get your parent's approval before proceeding."

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Log the event so parent can see it in Firebase
        val prefs    = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return
        FirebaseDatabase.getInstance()
            .getReference("events")
            .child(deviceId)
            .push()
            .setValue(mapOf(
                "type"      to "device_admin_disabled",
                "timestamp" to System.currentTimeMillis()
            ))
    }
}