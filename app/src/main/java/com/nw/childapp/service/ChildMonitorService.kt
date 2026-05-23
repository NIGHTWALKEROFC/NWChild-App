// PATH: nw-child-app/app/src/main/java/com/nw/childapp/service/ChildMonitorService.kt
package com.nw.childapp.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nw.childapp.data.CommandTypes
import com.nw.childapp.data.ControlCommand
import com.nw.childapp.data.repository.ChildRepository
import com.nw.childapp.util.PermissionHelper
import kotlinx.coroutines.*

/**
 * Persistent foreground service that:
 *  1. Keeps the Firebase command listener alive in the background
 *  2. Starts / stops Camera, Mic, and Screen services based on parent commands
 *  3. Uploads permission state to Firebase every 15 seconds
 *  4. Auto-restarts after being killed (START_STICKY)
 */
class ChildMonitorService : Service() {

    private val repo  = ChildRepository()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }

    private val deviceId: String get() = prefs.getString("device_id", "") ?: ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, buildNotification())

        if (deviceId.isNotEmpty()) {
            launchCommandListener()
            launchPermissionReporter()
            markOnline(true)
        }

        return START_STICKY   // OS will restart this if killed
    }

    // ── Command listener ─────────────────────────────────────────────

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
                launchCommandListener()   // restart listener on error
            }
        }
    }

    // ── Permission reporter ──────────────────────────────────────────

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

    private fun markOnline(online: Boolean) {
        scope.launch {
            try { repo.updateOnlineStatus(deviceId, online) }
            catch (e: Exception) { Log.e("ChildMonitor", "Online status error: ${e.message}") }
        }
    }

    // ── Command handler ──────────────────────────────────────────────

    private fun handleCommand(cmd: ControlCommand) {
        Log.d("ChildMonitor", "Command: ${cmd.type} value=${cmd.value}")
        when (cmd.type) {

            // Camera
            CommandTypes.ENABLE_CAMERA ->
                startForegroundService(Intent(this, CameraStreamService::class.java))
            CommandTypes.DISABLE_CAMERA ->
                stopService(Intent(this, CameraStreamService::class.java))

            // Microphone
            CommandTypes.ENABLE_MIC ->
                startForegroundService(Intent(this, MicStreamService::class.java))
            CommandTypes.DISABLE_MIC ->
                stopService(Intent(this, MicStreamService::class.java))

            // Screen share — activity must handle MediaProjection prompt
            CommandTypes.START_SCREEN_SHARE ->
                sendBroadcast(Intent("com.nw.childapp.START_SCREEN_SHARE"))
            CommandTypes.STOP_SCREEN_SHARE ->
                stopService(Intent(this, ScreenCaptureService::class.java))

            // App blocking
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

            // App time limits  value format = "com.pkg:60"
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

            // Disconnect (parent-approved or force)
            CommandTypes.APPROVE_DISCONNECT,
            CommandTypes.FORCE_DISCONNECT -> {
                scope.launch {
                    try { repo.markDisconnected(deviceId) } catch (_: Exception) {}
                }
                prefs.edit()
                    .putBoolean("is_paired", false)
                    .remove("parent_device_id")
                    .apply()
                // Notify UI
                sendBroadcast(Intent("com.nw.childapp.DISCONNECTED"))
                // Stop all child services
                stopService(Intent(this, CameraStreamService::class.java))
                stopService(Intent(this, MicStreamService::class.java))
                stopService(Intent(this, ScreenCaptureService::class.java))
                stopSelf()
            }

            // Parent-approved app deletion
            CommandTypes.APPROVE_DELETE ->
                sendBroadcast(Intent("com.nw.childapp.APPROVE_DELETE"))

            // Parent denied requests — UI handled in ViewModel via Firebase listener
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

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