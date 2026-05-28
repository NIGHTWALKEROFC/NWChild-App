// PATH: app/src/main/java/com/nw/childapp/service/NWAccessibilityService.kt
package com.nw.childapp.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.*

class NWAccessibilityService : AccessibilityService() {

    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }
    private val db    by lazy { FirebaseDatabase.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentPackage = ""
    private var lastTrackedUrl = ""

    private val browserPackages = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
        "com.brave.browser", "com.microsoft.emmx", "com.UCMobile.intl",
        "com.sec.android.app.sbrowser", "com.google.android.browser"
    )

    private val settingsPackages = setOf(
        "com.android.settings", "com.samsung.android.settings",
        "com.miui.securitycenter", "com.oneplus.settings"
    )

    companion object {
        var instance: NWAccessibilityService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
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
        Log.d("NWAccess", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == this.packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != currentPackage) {
                    currentPackage = pkg
                    checkAndEnforceApp(pkg)
                    interceptSettingsAccess(pkg, event.className?.toString() ?: "")
                }
                if (browserPackages.contains(pkg)) trackBrowserUrl(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (browserPackages.contains(pkg)) trackBrowserUrl(event)
            }
        }
    }

    private fun checkAndEnforceApp(pkg: String) {
        val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
        if (blocked.contains(pkg)) {
            Log.d("NWAccess", "Blocking: $pkg")
            performGlobalAction(GLOBAL_ACTION_HOME)
            showBlockNotification("App Blocked", "$pkg is blocked by your parent.")
            return
        }
        val limitMins = prefs.getInt("limit_$pkg", -1)
        if (limitMins > 0) {
            val usedMins = prefs.getInt("used_$pkg", 0)
            if (usedMins >= limitMins) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                showBlockNotification("Daily Limit Reached", "Daily limit for $pkg reached.")
            }
        }
    }

    private fun interceptSettingsAccess(pkg: String, className: String) {
        val isSettings = settingsPackages.contains(pkg)
        val appInfoClasses = listOf(
            "AppInfoDashboardFragment", "InstalledAppDetails",
            "AppStorageSettings", "AppInfoBase", "ManageApplicationsActivity"
        )
        val isAppInfo = appInfoClasses.any { className.contains(it, ignoreCase = true) }

        if (isSettings || isAppInfo) {
            val pin = prefs.getString("parent_pin", "") ?: ""
            if (pin.isNotEmpty()) {
                val intent = Intent(this, com.nw.childapp.ui.PinLockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("reason",
                        if (isAppInfo) "App settings are locked by your parent."
                        else "Device settings are locked by your parent."
                    )
                }
                startActivity(intent)
            }
        }
    }

    private fun trackBrowserUrl(event: AccessibilityEvent) {
        val deviceId = prefs.getString("device_id", null) ?: return
        try {
            val source = event.source ?: return
            val url = findUrlInNode(source) ?: return
            if (url == lastTrackedUrl || url.length < 6) return
            lastTrackedUrl = url
            scope.launch {
                db.getReference("browser_history").child(deviceId).push().setValue(
                    mapOf(
                        "url"       to url,
                        "pkg"       to currentPackage,
                        "timestamp" to ServerValue.TIMESTAMP
                    )
                )
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun findUrlInNode(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val text = node.text?.toString() ?: ""
        if ((text.startsWith("http://") || text.startsWith("https://") ||
             text.contains(".com") || text.contains(".in") || text.contains(".org")) &&
            !text.contains(" ") && text.length > 6) {
            return text
        }
        for (i in 0 until node.childCount) {
            val found = findUrlInNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    fun takeScreenshotNow(deviceId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val bmp = Bitmap.wrapHardwareBuffer(
                                    result.hardwareBuffer, result.colorSpace
                                )
                                if (bmp != null) {
                                    val soft = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                    DataSyncHelper.uploadScreenshot(soft, deviceId, scope)
                                }
                                result.hardwareBuffer.close()
                            } catch (e: Exception) {
                                Log.e("NWAccess", "Screenshot error: ${e.message}")
                            }
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.e("NWAccess", "Screenshot failed: $errorCode")
                    }
                }
            )
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun showBlockNotification(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = "nw_block"
        nm.createNotificationChannel(NotificationChannel(ch, "App Control", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(title.hashCode(),
            NotificationCompat.Builder(this, ch)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
        )
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("nw_acc", "NW Child Active", NotificationManager.IMPORTANCE_MIN))
        return NotificationCompat.Builder(this, "nw_acc")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NW Child Active").setContentText("Device protection running")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
    }
}