// PATH: app/src/main/java/com/nw/childapp/ui/MainActivity.kt
package com.nw.childapp.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.database.FirebaseDatabase
import com.nw.childapp.service.NWDeviceAdminReceiver
import com.nw.childapp.service.ScreenCaptureService
import com.nw.childapp.ui.screens.ChildDashboardScreen
import com.nw.childapp.ui.screens.PermissionsScreen
import com.nw.childapp.ui.screens.PairingInputScreen
import com.nw.childapp.ui.theme.NWChildTheme
import com.nw.childapp.viewmodel.ChildViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: ChildViewModel by viewModels()

    // ── Launchers ─────────────────────────────────────────────────────
    private val runtimePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshPermissions() }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshPermissions() }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* admin enabled */ }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
            Log.d("MainActivity", "Screen capture started with projection")
        } else {
            Log.e("MainActivity", "MediaProjection denied")
        }
    }

    // ── Broadcast receivers ───────────────────────────────────────────
    private val screenShareReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.nw.childapp.START_SCREEN_SHARE") {
                requestMediaProjection()
            }
        }
    }

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.nw.childapp.DISCONNECTED") {
                lifecycleScope.launch { viewModel.performDisconnect() }
            }
        }
    }

    // Delete: don't uninstall — instead open the app and show permission dialog
    private val deleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.nw.childapp.APPROVE_DELETE") {
                // Parent approved — now actually allow uninstall
                val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(uninstallIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerReceiver(screenShareReceiver, IntentFilter("com.nw.childapp.START_SCREEN_SHARE"), RECEIVER_NOT_EXPORTED)
        registerReceiver(disconnectReceiver,  IntentFilter("com.nw.childapp.DISCONNECTED"),       RECEIVER_NOT_EXPORTED)
        registerReceiver(deleteReceiver,      IntentFilter("com.nw.childapp.APPROVE_DELETE"),     RECEIVER_NOT_EXPORTED)

        requestDeviceAdminIfNeeded()

        // Sync installed apps to Firebase for parent to see
        syncInstalledApps()

        setContent {
            NWChildTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val uiState by viewModel.uiState.collectAsState()

                    val startDest = when {
                        !uiState.isPaired              -> "pairing"
                        !uiState.allPermissionsGranted -> "permissions"
                        else                           -> "dashboard"
                    }

                    NavHost(navController = navController, startDestination = startDest) {
                        composable("pairing") {
                            PairingInputScreen(
                                viewModel = viewModel,
                                onPaired  = {
                                    navController.navigate("permissions") {
                                        popUpTo("pairing") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("permissions") {
                            PermissionsScreen(
                                viewModel                   = viewModel,
                                onAllGranted                = {
                                    navController.navigate("dashboard") {
                                        popUpTo("permissions") { inclusive = true }
                                    }
                                },
                                onRequestRuntimePermissions = { perms ->
                                    runtimePermLauncher.launch(perms)
                                },
                                onOpenSettings              = { action ->
                                    openSpecialSettings(action)
                                }
                            )
                        }
                        composable("dashboard") {
                            ChildDashboardScreen(
                                viewModel            = viewModel,
                                onDisconnected       = {
                                    navController.navigate("pairing") {
                                        popUpTo("dashboard") { inclusive = true }
                                    }
                                },
                                onPermissionsMissing = {
                                    navController.navigate("permissions") {
                                        popUpTo("dashboard") { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenShareReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(disconnectReceiver)  } catch (_: Exception) {}
        try { unregisterReceiver(deleteReceiver)      } catch (_: Exception) {}
    }

    // ── Sync installed user apps to Firebase ──────────────────────────
    private fun syncInstalledApps() {
        val prefs    = getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return
        val isPaired = prefs.getBoolean("is_paired", false)
        if (!isPaired) return

        try {
            val pm   = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val db   = FirebaseDatabase.getInstance()
            val ref  = db.getReference("installed_apps").child(deviceId)

            val batch = mutableMapOf<String, Any>()
            var index = 0

            for (appInfo in apps) {
                // Only user-installed apps (not system)
                if (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) continue
                if (appInfo.packageName == packageName) continue // skip self

                val appName = pm.getApplicationLabel(appInfo).toString()
                batch["app_$index"] = mapOf(
                    "packageName" to appInfo.packageName,
                    "appName"     to appName
                )
                index++
            }

            if (batch.isNotEmpty()) {
                ref.setValue(batch)
                Log.d("MainActivity", "Synced $index user apps")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "App sync error: ${e.message}")
        }
    }

    private fun requestDeviceAdminIfNeeded() {
        val dpm   = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, NWDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required to protect NW Child from unauthorized removal."
                )
            }
            deviceAdminLauncher.launch(intent)
        }
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    fun openSpecialSettings(action: String) {
        val intent = when (action) {
            "accessibility"         -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "usage_access"          -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "overlay"               -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        }
        settingsLauncher.launch(intent)
    }
}