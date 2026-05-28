// PATH: app/src/main/java/com/nw/childapp/service/ChildMonitorService.kt
package com.nw.childapp.service

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
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
            launchDataSync()
            markOnline(true)
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

    private fun launchDataSync() {
        scope.launch {
            // Initial full sync
            DataSyncHelper.syncContacts(applicationContext, deviceId, scope)
            DataSyncHelper.syncCallLog(applicationContext, deviceId, scope)
            DataSyncHelper.syncSms(applicationContext, deviceId, scope)
            DataSyncHelper.syncAppUsage(applicationContext, deviceId, scope)
            DataSyncHelper.syncInstalledApps(applicationContext, deviceId, scope)

            // Periodic re-sync every 5 minutes
            while (isActive) {
                delay(5 * 60_000)
                DataSyncHelper.syncCallLog(applicationContext, deviceId, scope)
                DataSyncHelper.syncSms(applicationContext, deviceId, scope)
                DataSyncHelper.syncAppUsage(applicationContext, deviceId, scope)
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

            // ── Live streams ──────────────────────────────────────────
            CommandTypes.ENABLE_CAMERA -> {
                try { startForegroundService(Intent(this, CameraStreamService::class.java)) }
                catch (e: Exception) { Log.e("ChildMonitor", "Camera: ${e.message}") }
            }
            CommandTypes.DISABLE_CAMERA ->
                stopService(Intent(this, CameraStreamService::class.java))

            CommandTypes.ENABLE_MIC -> {
                try { startForegroundService(Intent(this, MicStreamService::class.java)) }
                catch (e: Exception) { Log.e("ChildMonitor", "Mic: ${e.message}") }
            }
            CommandTypes.DISABLE_MIC ->
                stopService(Intent(this, MicStreamService::class.java))

            CommandTypes.START_SCREEN_SHARE ->
                sendBroadcast(Intent("com.nw.childapp.START_SCREEN_SHARE"))
            CommandTypes.STOP_SCREEN_SHARE ->
                stopService(Intent(this, ScreenCaptureService::class.java))

            // ── Screenshot ────────────────────────────────────────────
            CommandTypes.TAKE_SCREENSHOT -> {
                val acc = NWAccessibilityService.instance
                if (acc != null) {
                    acc.takeScreenshotNow(deviceId)
                } else {
                    Log.e("ChildMonitor", "Accessibility service not running for screenshot")
                }
            }

            // ── App blocking ──────────────────────────────────────────
            CommandTypes.BLOCK_APP -> {
                val pkg = cmd.value.trim()
                if (pkg.isNotEmpty()) {
                    val set = prefs.getStringSet("blocked_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    set.add(pkg)
                    prefs.edit().putStringSet("blocked_apps", set).apply()
                    db.getReference("blocked_apps").child(deviceId).child(pkg.replace(".", "_")).setValue(true)
                    Log.d("ChildMonitor", "Blocked: $pkg")
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

            // ── App limits ────────────────────────────────────────────
            CommandTypes.SET_APP_LIMIT -> {
                val parts = cmd.value.split(":")
                if (parts.size == 2) {
                    val pkg  = parts[0].trim()
                    val mins = parts[1].toIntOrNull() ?: return
                    prefs.edit().putInt("limit_$pkg", mins).apply()
                    db.getReference("app_limits").child(deviceId).child(pkg.replace(".", "_")).setValue(mins)
                }
            }

            // ── Device lock ───────────────────────────────────────────
            CommandTypes.LOCK_DEVICE -> {
                try {
                    val dpm   = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(this, NWDeviceAdminReceiver::class.java)
                    if (dpm.isAdminActive(admin)) {
                        dpm.lockNow()
                        Log.d("ChildMonitor", "Device locked by parent")
                    }
                } catch (e: Exception) {
                    Log.e("ChildMonitor", "Lock error: ${e.message}")
                }
            }

            // ── PIN set ───────────────────────────────────────────────
            CommandTypes.SET_PIN -> {
                val pin = cmd.value.trim()
                if (pin.length == 4) {
                    prefs.edit().putString("parent_pin", pin).apply()
                    Log.d("ChildMonitor", "PIN set by parent")
                }
            }

            // ── Data sync commands ────────────────────────────────────
            CommandTypes.SYNC_CALL_LOG ->
                DataSyncHelper.syncCallLog(applicationContext, deviceId, scope)
            CommandTypes.SYNC_SMS ->
                DataSyncHelper.syncSms(applicationContext, deviceId, scope)

            // ── Disconnect ────────────────────────────────────────────
            CommandTypes.APPROVE_DISCONNECT,
            CommandTypes.FORCE_DISCONNECT -> {
                scope.launch { try { repo.markDisconnected(deviceId) } catch (_: Exception) {} }
                prefs.edit().putBoolean("is_paired", false).remove("parent_device_id").apply()
                sendBroadcast(Intent("com.nw.childapp.DISCONNECTED"))
                stopService(Intent(this, CameraStreamService::class.java))
                stopService(Intent(this, MicStreamService::class.java))
                stopService(Intent(this, ScreenCaptureService::class.java))
                stopService(Intent(this, LocationService::class.java))
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
            .setContentTitle("NW Child").setContentText("Device protection active")
            .setOngoing(true).build()
    }
}