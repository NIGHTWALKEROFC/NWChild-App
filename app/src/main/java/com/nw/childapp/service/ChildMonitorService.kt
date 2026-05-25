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
            syncAllContactsToFirebase()
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
                delay(10_000)
            }
        }
    }

    // Sync ALL contacts — no limit, reads every contact on device
    private fun syncAllContactsToFirebase() {
        scope.launch(Dispatchers.IO) {
            try {
                val seen = mutableSetOf<String>() // deduplicate by name+number
                val batch = mutableMapOf<String, Any>()
                var index = 0

                val cursor: Cursor? = applicationContext.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )

                cursor?.use { c ->
                    val nameIdx   = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val name   = (c.getString(nameIdx)   ?: "Unknown").trim()
                        val number = (c.getString(numberIdx) ?: "").trim()
                        val key    = "$name|$number"
                        if (key !in seen) {
                            seen.add(key)
                            batch["c$index"] = mapOf("name" to name, "number" to number)
                            index++
                        }
                    }
                }

                if (batch.isNotEmpty()) {
                    val contactsRef = db.getReference("contacts").child(deviceId)
                    // Remove old then batch-write all
                    contactsRef.removeValue().addOnCompleteListener {
                        contactsRef.updateChildren(batch)
                        Log.d("ChildMonitor", "Synced $index unique contacts")
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
            catch (e: Exception) { Log.e("ChildMonitor", "Online error") }
        }
    }

    private fun handleCommand(cmd: ControlCommand) {
        Log.d("ChildMonitor", "CMD: ${cmd.type} val=${cmd.value}")
        when (cmd.type) {
            CommandTypes.ENABLE_CAMERA -> {
                try { startForegroundService(Intent(this, CameraStreamService::class.java)) }
                catch (e: Exception) { Log.e("ChildMonitor", "Camera start: ${e.message}") }
            }
            CommandTypes.DISABLE_CAMERA ->
                stopService(Intent(this, CameraStreamService::class.java))

            CommandTypes.ENABLE_MIC -> {
                try { startForegroundService(Intent(this, MicStreamService::class.java)) }
                catch (e: Exception) { Log.e("ChildMonitor", "Mic start: ${e.message}") }
            }
            CommandTypes.DISABLE_MIC ->
                stopService(Intent(this, MicStreamService::class.java))

            CommandTypes.START_SCREEN_SHARE ->
                sendBroadcast(Intent("com.nw.childapp.START_SCREEN_SHARE"))
            CommandTypes.STOP_SCREEN_SHARE ->
                stopService(Intent(this, ScreenCaptureService::class.java))

            CommandTypes.BLOCK_APP -> {
                val pkg = cmd.value.trim()
                if (pkg.isNotEmpty()) {
                    val set = prefs.getStringSet("blocked_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    set.add(pkg)
                    prefs.edit().putStringSet("blocked_apps", set).apply()
                    db.getReference("blocked_apps").child(deviceId).child(pkg.replace(".", "_")).setValue(true)
                    Log.d("ChildMonitor", "Blocked: $pkg total=${set.size}")
                }
            }
            CommandTypes.UNBLOCK_APP -> {
                val pkg = cmd.value.trim()
                if (pkg.isNotEmpty()) {
                    val set = prefs.getStringSet("blocked_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    set.remove(pkg)
                    prefs.edit().putStringSet("blocked_apps", set).apply()
                    db.getReference("blocked_apps").child(deviceId).child(pkg.replace(".", "_")).removeValue()
                    Log.d("ChildMonitor", "Unblocked: $pkg")
                }
            }

            CommandTypes.SET_APP_LIMIT -> {
                val parts = cmd.value.split(":")
                if (parts.size == 2) {
                    val pkg  = parts[0].trim()
                    val mins = parts[1].toIntOrNull() ?: return
                    prefs.edit().putInt("limit_$pkg", mins).apply()
                    db.getReference("app_limits").child(deviceId).child(pkg.replace(".", "_")).setValue(mins)
                    Log.d("ChildMonitor", "Limit $pkg = $mins min")
                }
            }

            CommandTypes.APPROVE_DISCONNECT,
            CommandTypes.FORCE_DISCONNECT -> {
                scope.launch { try { repo.markDisconnected(deviceId) } catch (_: Exception) {} }
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
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("nw_monitor", "NW Child Monitor", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, "nw_monitor")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NW Child")
            .setContentText("Device protection active")
            .setOngoing(true)
            .build()
    }
}