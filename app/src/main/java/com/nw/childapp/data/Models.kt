// PATH: nw-child-app/app/src/main/java/com/nw/childapp/data/Models.kt
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
    val usageStats: Boolean    = false
) {
    /** True only when every permission is granted */
    fun allGranted(): Boolean =
        camera && microphone && screenShare && storage &&
        notifications && contacts && accessibility && usageStats

    /** Returns list of PermissionItem values that are still missing */
    fun missingList(): List<PermissionItem> = buildList {
        if (!camera)        add(PermissionItem.CAMERA)
        if (!microphone)    add(PermissionItem.MICROPHONE)
        if (!screenShare)   add(PermissionItem.SCREEN_SHARE)
        if (!storage)       add(PermissionItem.STORAGE)
        if (!notifications) add(PermissionItem.NOTIFICATIONS)
        if (!contacts)      add(PermissionItem.CONTACTS)
        if (!accessibility) add(PermissionItem.ACCESSIBILITY)
        if (!usageStats)    add(PermissionItem.USAGE_STATS)
    }
}

/**
 * Each enum value represents one required permission.
 * requiresSettings = true means the user must be sent to a system Settings screen
 * because Android does not expose a runtime dialog for it.
 */
enum class PermissionItem(
    val label: String,
    val description: String,
    val requiresSettings: Boolean = false
) {
    CAMERA(
        "Camera Access",
        "Allows live camera monitoring by parent"
    ),
    MICROPHONE(
        "Microphone Access",
        "Allows live voice monitoring by parent"
    ),
    SCREEN_SHARE(
        "Screen Share",
        "Allows live screen viewing by parent",
        requiresSettings = false  // requested via MediaProjection at runtime
    ),
    STORAGE(
        "Storage Access",
        "Allows file access monitoring"
    ),
    NOTIFICATIONS(
        "Notification Access",
        "Allows parent to see notification activity",
        requiresSettings = true
    ),
    CONTACTS(
        "Contact Access",
        "Allows parent to view contact list"
    ),
    ACCESSIBILITY(
        "Accessibility Service",
        "Required for app blocking and usage control",
        requiresSettings = true
    ),
    USAGE_STATS(
        "App Usage Stats",
        "Required for daily app time limits",
        requiresSettings = true
    )
}

data class ControlCommand(
    val type: String       = "",
    val value: String      = "",
    val timestamp: Long    = 0L,
    val fromParent: Boolean = true,
    val expiresAt: Long    = 0L
)

data class UpdateInfo(
    val versionCode: Int   = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val mandatory: Boolean  = false
)

object CommandTypes {
    const val ENABLE_CAMERA      = "enable_camera"
    const val DISABLE_CAMERA     = "disable_camera"
    const val ENABLE_MIC         = "enable_mic"
    const val DISABLE_MIC        = "disable_mic"
    const val START_SCREEN_SHARE = "start_screen_share"
    const val STOP_SCREEN_SHARE  = "stop_screen_share"
    const val BLOCK_APP          = "block_app"
    const val UNBLOCK_APP        = "unblock_app"
    const val SET_APP_LIMIT      = "set_app_limit"
    const val APPROVE_DISCONNECT = "approve_disconnect"
    const val DENY_DISCONNECT    = "deny_disconnect"
    const val APPROVE_DELETE     = "approve_delete"
    const val DENY_DELETE        = "deny_delete"
    const val FORCE_DISCONNECT   = "force_disconnect"
}