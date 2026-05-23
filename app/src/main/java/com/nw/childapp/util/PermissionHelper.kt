// PATH: nw-child-app/app/src/main/java/com/nw/childapp/util/PermissionHelper.kt
package com.nw.childapp.util

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.nw.childapp.data.ChildPermissions
import com.nw.childapp.service.NWAccessibilityService
import com.nw.childapp.service.NWDeviceAdminReceiver

object PermissionHelper {

    /** Build a full ChildPermissions snapshot from the current device state */
    fun checkAllPermissions(context: Context): ChildPermissions = ChildPermissions(
        camera        = hasPermission(context, android.Manifest.permission.CAMERA),
        microphone    = hasPermission(context, android.Manifest.permission.RECORD_AUDIO),
        screenShare   = true,   // MediaProjection is requested at runtime; treat as always available
        storage       = checkStorage(context),
        notifications = checkNotificationListener(context),
        contacts      = hasPermission(context, android.Manifest.permission.READ_CONTACTS),
        accessibility = checkAccessibility(context),
        usageStats    = checkUsageStats(context)
    )

    // ── Individual checks ─────────────────────────────────────────────

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun checkStorage(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) &&
            hasPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            hasPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun checkAccessibility(context: Context): Boolean {
        // Primary check via settings string (most reliable)
        val setting = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
        } catch (e: Exception) { "" }

        val target = "${context.packageName}/${NWAccessibilityService::class.java.name}"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(setting)
        while (splitter.hasNext()) {
            if (splitter.next().equals(target, ignoreCase = true)) return true
        }
        return false
    }

    fun checkNotificationListener(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(context.packageName)
    }

    fun checkUsageStats(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(context, NWDeviceAdminReceiver::class.java))
    }

    // ── Runtime permission list ───────────────────────────────────────

    /** Returns array of runtime permissions that still need to be requested */
    fun missingRuntimePermissions(context: Context): Array<String> = buildList {
        if (!hasPermission(context, android.Manifest.permission.CAMERA))
            add(android.Manifest.permission.CAMERA)
        if (!hasPermission(context, android.Manifest.permission.RECORD_AUDIO))
            add(android.Manifest.permission.RECORD_AUDIO)
        if (!hasPermission(context, android.Manifest.permission.READ_CONTACTS))
            add(android.Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(context, android.Manifest.permission.POST_NOTIFICATIONS))
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            if (!hasPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES))
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
            if (!hasPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO))
                add(android.Manifest.permission.READ_MEDIA_VIDEO)
            if (!hasPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO))
                add(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            if (!hasPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE))
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()
}