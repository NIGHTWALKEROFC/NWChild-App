// PATH: app/src/main/java/com/nw/childapp/service/ChildMonitorService.kt
package com.nw.childapp.service

import android.app.*
import android.content.Intent
import android.database.Cursor
import android.os.IBinder
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.nw.childapp.data.CommandTypes
import com.nw.childapp.data.ControlCommand
import com.nw.childapp.data.repository.ChildRepository
import com.nw.childapp.util.PermissionHelper
import kotlinx.coroutines.*

class ChildMonitorService : Service() {

    private val repo  = ChildRepository()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }
    private val db    by lazy { FirebaseDatabase.getInstance() }

    private val deviceId: String get() = prefs.getString("device_id", "") ?: ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, buildNotification())
        if (deviceId.isNotEmpty()) {
            launchCommandListener()
            launchPermissionReporter()
            markOnline(true)
            // Sync contacts immediately on start
            syncContactsToFirebase()
        }
        return START_STICKY
    }

    private fun launchCommandListener() {
        scope.launch {
            try {
                repo.listenForCommands(deviceId).collect { cmd ->
                    cmd ?: return@collect
                    handleCommand(cmd)
                }
            } catch (e: Exception) {
                Log.e("ChildMonitor", "Listener error: ${e.message}")
                delay(5_000)
                launchCommandListener()
            }
        }
    }

    private fun launchPermissionReporter() {
        scope.launch {
            while (isActive) {
                try {
                    val perms = PermissionHelper.checkAllPermissions(applicationContext)
                    repo.updatePermissions(deviceId, perms)
                    repo.updateOnlineStatus(deviceId, true)
                } catch (e: Exception) {
                    Log.e("ChildMonitor", "Reporter error: ${e.message}")
                }
                delay(15_000)
            }
        }
    }

    // ── Contacts sync — reads ALL contacts and uploads to Firebase ────
    private fun syncContactsToFirebase() {
        scope.launch(Dispatchers.IO) {
            try {
                val contactsRef = db.getReference("contacts").child(deviceId)

                // First clear old data
                contactsRef.removeValue().addOnCompleteListener {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val cursor: Cursor? = applicationContext.contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(
                                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                    ContactsContract.CommonDataKinds.Phone.NUMBER
                                ),
                                null, null,
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                            )

                            val batch = mutableMapOf<String, Any>()
                            var index = 0

                            cursor?.use { c ->
                                val nameIdx   = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                                val numberIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                while (c.moveToNext()) {
                                    val name   = c.getString(nameIdx)   ?: "Unknown"
                                    val number = c.getString(numberIdx) ?: ""
                                    batch["contact_$index"] = mapOf("name" to name, "number" to number)
                                    index++
                                }
                            }

                            if (batch.isNotEmpty()) {
                                contactsRef.updateChildren(batch)
                                Log.d("ChildMonitor", "Synced $index contacts to Firebase")
                            }
                        } catch (e: Exception) {
                            Log.e("ChildMonitor", "Contact upload error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChildMonitor", "Contact sync error: ${e.message}")
            }
        }
    }

    private fun markOnline(online: Boolean) {
        scope.launch {
            try { repo.updateOnlineStatus(deviceId, online) }
            catch (e: Exception) { Log.e("ChildMonitor", "Online error: ${e.message}") }
        }
    }

    private fun handleCommand(cmd: ControlCommand) {
        Log.d("ChildMonitor", "CMD: ${cmd.type} = ${cmd.value}")
        when (cmd.type) {
            CommandTypes.ENABLE_CAMERA -> {
                try { startForegroundService(Intent(this, CameraStreamService::class.java)) }
                catch (e: Exception) { Log.e("ChildMonitor", "Camera start error: ${e.message}") }
            }
            CommandTypes.DISABLE_CAMERA ->
                stopService(Intent(this, CameraStreamService::class.java))

            CommandTypes.ENABLE_MIC -> {
                try { startForegroundService(Intent(this, MicStreamService::class.java)) }
                catch (e: Exception) { Log.e("ChildMonitor", "Mic start error: ${e.message}") }
            }
            CommandTypes.DISABLE_MIC ->
                stopService(Intent(this, MicStreamService::class.java))

            CommandTypes.START_SCREEN_SHARE ->
                sendBroadcast(Intent("com.nw.childapp.START_SCREEN_SHARE"))
            CommandTypes.STOP_SCREEN_SHARE ->
                stopService(Intent(this, ScreenCaptureService::class.java))

            // App blocking — write to prefs, accessibility service reads it
            CommandTypes.BLOCK_APP -> {
                val pkg = cmd.value.trim()
                if (pkg.isNotEmpty()) {
                    val blocked = prefs.getStringSet("blocked_apps", mutableSetOf())
                        ?.toMutableSet() ?: mutableSetOf()
                    blocked.add(pkg)
                    prefs.edit().putStringSet("blocked_apps", blocked).apply()
                    Log.d("ChildMonitor", "Blocked: $pkg — total: ${blocked.size}")
                    // Also store in Firebase for parent confirmation
                    db.getReference("blocked_apps").child(deviceId).child(pkg).setValue(true)
                }
            }
            CommandTypes.UNBLOCK_APP -> {
                val pkg = cmd.value.trim()
                if (pkg.isNotEmpty()) {
                    val blocked = prefs.getStringSet("blocked_apps", mutableSetOf())
                        ?.toMutableSet() ?: mutableSetOf()
                    blocked.remove(pkg)
                    prefs.edit().putStringSet("blocked_apps", blocked).apply()
                    Log.d("ChildMonitor", "Unblocked: $pkg")
                    db.getReference("blocked_apps").child(deviceId).child(pkg).removeValue()
                }
            }

            CommandTypes.SET_APP_LIMIT -> {
                val parts = cmd.value.split(":")
                if (parts.size == 2) {
                    val pkg  = parts[0].trim()
                    val mins = parts[1].toIntOrNull() ?: return
                    // Store limit in prefs for accessibility service
                    prefs.edit().putInt("limit_$pkg", mins).apply()
                    Log.d("ChildMonitor", "Set limit $pkg = $mins min")
                    db.getReference("app_limits").child(deviceId).child(pkg).setValue(mins)
                }
            }

            CommandTypes.APPROVE_DISCONNECT,
            CommandTypes.FORCE_DISCONNECT -> {
                scope.launch {
                    try { repo.markDisconnected(deviceId) } catch (_: Exception) {}
                }
                prefs.edit().putBoolean("is_paired", false).remove("parent_device_id").apply()
                sendBroadcast(Intent("com.nw.childapp.DISCONNECTED"))
                stopService(Intent(this, CameraStreamService::class.java))
                stopService(Intent(this, MicStreamService::class.java))
                stopService(Intent(this, ScreenCaptureService::class.java))
                stopSelf()
            }
            CommandTypes.APPROVE_DELETE ->
                sendBroadcast(Intent("com.nw.childapp.APPROVE_DELETE"))
        }
    }

    override fun onDestroy() {
        markOnline(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "nw_monitor"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "NW Child Monitor", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NW Child")
            .setContentText("Device protection active")
            .setOngoing(true)
            .build()
    }
}