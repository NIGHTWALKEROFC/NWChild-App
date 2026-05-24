// PATH: app/src/main/java/com/nw/childapp/ui/MainActivity.kt
package com.nw.childapp.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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

    // ── Runtime permissions ───────────────────────────────────────────
    private val runtimePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshPermissions() }

    // ── Special settings ──────────────────────────────────────────────
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshPermissions() }

    // ── Device admin ──────────────────────────────────────────────────
    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* admin enabled/denied */ }

    // ── MediaProjection for screen share ──────────────────────────────
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
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

    private val deleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.nw.childapp.APPROVE_DELETE") {
                initiateUninstall()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register receivers
        registerReceiver(screenShareReceiver, IntentFilter("com.nw.childapp.START_SCREEN_SHARE"), RECEIVER_NOT_EXPORTED)
        registerReceiver(disconnectReceiver,  IntentFilter("com.nw.childapp.DISCONNECTED"),       RECEIVER_NOT_EXPORTED)
        registerReceiver(deleteReceiver,      IntentFilter("com.nw.childapp.APPROVE_DELETE"),     RECEIVER_NOT_EXPORTED)

        requestDeviceAdminIfNeeded()

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

    // ── Helpers ───────────────────────────────────────────────────────

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
            "accessibility" -> {
                // Direct to accessibility settings — user enables NW Child Monitor there
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
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

    private fun initiateUninstall() {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}