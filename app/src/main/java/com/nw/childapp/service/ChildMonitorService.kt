// PATH: app/src/main/java/com/nw/childapp/service/ChildMonitorService.kt
package com.nw.childapp.service

import android.app.*
import android.content.Intent
import android.database.Cursor
import android.os.IBinder
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nw.childapp.data.CommandTypes
import com.nw.childapp.data.ControlCommand
import com.nw.childapp.data.repository.ChildRepository
import com.nw.childapp.util.PermissionHelper
import com.google.firebase.database.FirebaseDatabase
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
                Log.e("ChildMonitor", "Command listener error: ${e.message}")
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
                    Log.e("ChildMonitor", "Permission report error: ${e.message}")
                }
                delay(15_000)
            }
        }
    }

    // ── Sync contacts to Firebase so parent can view them ─────────────
    private fun syncContactsToFirebase() {
        scope.launch {
            try {
                val contacts = mutableListOf<Map<String, String>>()
                val cursor: Cursor? = applicationContext.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )
                cursor?.use {
                    val nameIdx   = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (it.moveToNext()) {
                        val name   = it.getString(nameIdx)   ?: continue
                        val number = it.getString(numberIdx) ?: ""
                        contacts.add(mapOf("name" to name, "number" to number))
                    }
                }
                // Upload to Firebase
                val contactsRef = db.getReference("contacts").child(deviceId)
                contactsRef.removeValue()
                contacts.forEachIndexed { index, contact ->
                    contactsRef.child("contact_$index").setValue(contact)
                }
                Log.d("ChildMonitor", "Synced ${contacts.size} contacts")
            } catch (e: Exception) {
                Log.e("ChildMonitor", "Contact sync error: ${e.message}")
            }
        }
    }

    private fun markOnline(online: Boolean) {
        scope.launch {
            try { repo.updateOnlineStatus(deviceId, online) }
            catch (e: Exception) { Log.e("ChildMonitor", "Online status error: ${e.message}") }
        }
    }

    private fun handleCommand(cmd: ControlCommand) {
        Log.d("ChildMonitor", "Command: ${cmd.type} value=${cmd.value}")
        when (cmd.type) {
            CommandTypes.ENABLE_CAMERA ->
                startForegroundService(Intent(this, CameraStreamService::class.java))
            CommandTypes.DISABLE_CAMERA ->
                stopService(Intent(this, CameraStreamService::class.java))
            CommandTypes.ENABLE_MIC ->
                startForegroundService(Intent(this, MicStreamService::class.java))
            CommandTypes.DISABLE_MIC ->
                stopService(Intent(this, MicStreamService::class.java))
            CommandTypes.START_SCREEN_SHARE ->
                sendBroadcast(Intent("com.nw.childapp.START_SCREEN_SHARE"))
            CommandTypes.STOP_SCREEN_SHARE ->
                stopService(Intent(this, ScreenCaptureService::class.java))
            CommandTypes.BLOCK_APP -> {
                val blocked = prefs.getStringSet("blocked_apps", mutableSetOf())
                    ?.toMutableSet() ?: mutableSetOf()
                blocked.add(cmd.value)
                prefs.edit().putStringSet("blocked_apps", blocked).apply()
            }
            CommandTypes.UNBLOCK_APP -> {
                val blocked = prefs.getStringSet("blocked_apps", mutableSetOf())
                    ?.toMutableSet() ?: mutableSetOf()
                blocked.remove(cmd.value)
                prefs.edit().putStringSet("blocked_apps", blocked).apply()
            }
            CommandTypes.SET_APP_LIMIT -> {
                val parts = cmd.value.split(":")
                if (parts.size == 2) {
                    val pkg   = parts[0]
                    val mins  = parts[1].toIntOrNull() ?: return
                    val json  = prefs.getString("app_limits", "{}") ?: "{}"
                    val entry = "\"$pkg\":$mins"
                    val updated = if (json == "{}") "{$entry}"
                                  else json.replace("}", ",$entry}")
                    prefs.edit().putString("app_limits", updated).apply()
                }
            }
            CommandTypes.APPROVE_DISCONNECT,
            CommandTypes.FORCE_DISCONNECT -> {
                scope.launch {
                    try { repo.markDisconnected(deviceId) } catch (_: Exception) {}
                }
                prefs.edit()
                    .putBoolean("is_paired", false)
                    .remove("parent_device_id")
                    .apply()
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