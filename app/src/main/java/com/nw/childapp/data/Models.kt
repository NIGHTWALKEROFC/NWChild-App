// PATH: app/src/main/java/com/nw/childapp/data/Models.kt
package com.nw.childapp.data

data class ChildDevice(
    val deviceId: String        = "",
    val deviceName: String      = "",
    val connectedAt: Long       = 0L,
    val parentDeviceId: String  = "",
    val fcmToken: String        = "",
    val permissions: ChildPermissions = ChildPermissions(),
    val isOnline: Boolean       = false,
    val isConnected: Boolean    = false,
    val lastSeen: Long          = 0L
)

data class ChildPermissions(
    val camera: Boolean        = false,
    val microphone: Boolean    = false,
    val screenShare: Boolean   = false,
    val storage: Boolean       = false,
    val notifications: Boolean = false,
    val contacts: Boolean      = false,
    val accessibility: Boolean = false,
    val usageStats: Boolean    = false,
    val callLog: Boolean       = false,
    val sms: Boolean           = false,
    val location: Boolean      = false,
    val overlay: Boolean       = false
) {
    fun allGranted(): Boolean =
        camera && microphone && screenShare && storage &&
        notifications && contacts && accessibility && usageStats &&
        callLog && sms && location && overlay

    fun missingList(): List<PermissionItem> = buildList {
        if (!camera)        add(PermissionItem.CAMERA)
        if (!microphone)    add(PermissionItem.MICROPHONE)
        if (!screenShare)   add(PermissionItem.SCREEN_SHARE)
        if (!storage)       add(PermissionItem.STORAGE)
        if (!notifications) add(PermissionItem.NOTIFICATIONS)
        if (!contacts)      add(PermissionItem.CONTACTS)
        if (!accessibility) add(PermissionItem.ACCESSIBILITY)
        if (!usageStats)    add(PermissionItem.USAGE_STATS)
        if (!callLog)       add(PermissionItem.CALL_LOG)
        if (!sms)           add(PermissionItem.SMS)
        if (!location)      add(PermissionItem.LOCATION)
        if (!overlay)       add(PermissionItem.OVERLAY)
    }
}

enum class PermissionItem(
    val label: String,
    val description: String,
    val requiresSettings: Boolean = false
) {
    CAMERA("Camera Access", "Allows live camera monitoring"),
    MICROPHONE("Microphone Access", "Allows live voice monitoring"),
    SCREEN_SHARE("Screen Share", "Allows live screen viewing"),
    STORAGE("Storage Access", "Allows file access monitoring"),
    NOTIFICATIONS("Notification Access", "Monitors notifications", requiresSettings = true),
    CONTACTS("Contact Access", "Allows contact list access"),
    ACCESSIBILITY("Accessibility Service", "Required for app control", requiresSettings = true),
    USAGE_STATS("App Usage Stats", "Required for time limits", requiresSettings = true),
    CALL_LOG("Call Log Access", "Parent sees call history"),
    SMS("SMS Access", "Parent sees text messages"),
    LOCATION("Location Access", "Real-time GPS tracking"),
    OVERLAY("Display Over Apps", "Required for PIN lock & protection", requiresSettings = true)
}

data class ControlCommand(
    val type: String        = "",
    val value: String       = "",
    val timestamp: Long     = 0L,
    val fromParent: Boolean = true,
    val expiresAt: Long     = 0L
)

data class UpdateInfo(
    val versionCode: Int    = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val mandatory: Boolean  = false
)

data class LocationData(
    val lat: Double      = 0.0,
    val lng: Double      = 0.0,
    val accuracy: Float  = 0f,
    val timestamp: Long  = 0L,
    val address: String  = ""
)

data class CallLogEntry(
    val number: String   = "",
    val name: String     = "",
    val type: String     = "",  // INCOMING, OUTGOING, MISSED
    val duration: Long   = 0L,
    val timestamp: Long  = 0L
)

data class SmsEntry(
    val number: String   = "",
    val name: String     = "",
    val body: String     = "",
    val type: String     = "",  // INBOX, SENT
    val timestamp: Long  = 0L
)

data class AppUsageEntry(
    val packageName: String = "",
    val appName: String     = "",
    val usageMinutes: Int   = 0,
    val date: String        = ""  // yyyy-MM-dd
)

object CommandTypes {
    const val ENABLE_CAMERA       = "enable_camera"
    const val DISABLE_CAMERA      = "disable_camera"
    const val ENABLE_MIC          = "enable_mic"
    const val DISABLE_MIC         = "disable_mic"
    const val START_SCREEN_SHARE  = "start_screen_share"
    const val STOP_SCREEN_SHARE   = "stop_screen_share"
    const val BLOCK_APP           = "block_app"
    const val UNBLOCK_APP         = "unblock_app"
    const val SET_APP_LIMIT       = "set_app_limit"
    const val APPROVE_DISCONNECT  = "approve_disconnect"
    const val DENY_DISCONNECT     = "deny_disconnect"
    const val APPROVE_DELETE      = "approve_delete"
    const val DENY_DELETE         = "deny_delete"
    const val FORCE_DISCONNECT    = "force_disconnect"
    const val LOCK_DEVICE         = "lock_device"
    const val UNLOCK_DEVICE       = "unlock_device"
    const val SET_PIN             = "set_pin"
    const val TAKE_SCREENSHOT     = "take_screenshot"
    const val SYNC_CALL_LOG       = "sync_call_log"
    const val SYNC_SMS            = "sync_sms"
}