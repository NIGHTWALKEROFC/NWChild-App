// PATH: app/src/main/java/com/nw/childapp/ui/screens/PermissionsScreen.kt
package com.nw.childapp.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nw.childapp.data.PermissionItem
import com.nw.childapp.ui.theme.*
import com.nw.childapp.viewmodel.ChildViewModel

@Composable
fun PermissionsScreen(
    viewModel: ChildViewModel,
    onAllGranted: () -> Unit,
    onRequestRuntimePermissions: (Array<String>) -> Unit,
    onOpenSettings: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAccessibilityGuide by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.allPermissionsGranted) {
        if (uiState.allPermissionsGranted) onAllGranted()
    }

    val perms   = uiState.permissions
    val missing = perms.missingList()
    val total   = PermissionItem.values().size
    val granted = total - missing.size

    if (showAccessibilityGuide) {
        AccessibilityGuideDialog(
            onDismiss     = { showAccessibilityGuide = false },
            onOpenSettings = {
                showAccessibilityGuide = false
                onOpenSettings("accessibility")
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(ChildBackground, Color(0xFF040D04))))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(52.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(46.dp).clip(CircleShape)
                        .background(ChildAccent.copy(0.15f))
                        .border(1.5.dp, ChildAccent.copy(0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Security, null, tint = ChildAccent, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Setup Required", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = ChildOnBackground)
                    Text("Grant all permissions to continue", fontSize = 12.sp, color = ChildOnSurface)
                }
            }

            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(16.dp),
                colors   = CardDefaults.cardColors(containerColor = ChildCard)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Permissions granted", fontSize = 13.sp, color = ChildOnSurface)
                        Text("$granted / $total", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = if (missing.isEmpty()) ChildSuccess else ChildWarning)
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress   = granted.toFloat() / total,
                        modifier   = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color      = if (missing.isEmpty()) ChildSuccess else ChildWarning,
                        trackColor = ChildSurface
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            if (missing.isNotEmpty()) {
                Text("Still needed", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = ChildError, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                missing.forEach { item ->
                    PermCard(
                        item      = item,
                        isGranted = false,
                        onGrant   = {
                            if (item == PermissionItem.ACCESSIBILITY) {
                                showAccessibilityGuide = true
                            } else {
                                grantItem(item, onRequestRuntimePermissions, onOpenSettings)
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            AnimatedVisibility(visible = granted > 0, enter = fadeIn(tween(400)), exit = fadeOut()) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text("Granted", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = ChildSuccess, letterSpacing = 1.sp)
                    Spacer(Modifier.height(10.dp))
                    PermissionItem.values()
                        .filter { !missing.contains(it) }
                        .forEach { item ->
                            PermCard(item = item, isGranted = true, onGrant = {})
                            Spacer(Modifier.height(8.dp))
                        }
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick  = { viewModel.refreshPermissions() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = if (missing.isEmpty()) ChildAccent else ChildSurface
                ),
                border = if (missing.isEmpty()) null else BorderStroke(1.dp, ChildAccent.copy(0.4f))
            ) {
                Icon(Icons.Default.Refresh, null,
                    tint = if (missing.isEmpty()) Color(0xFF003300) else ChildAccent)
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = if (missing.isEmpty()) "All done — Continue" else "Refresh Permissions",
                    fontWeight = FontWeight.Bold,
                    color      = if (missing.isEmpty()) Color(0xFF003300) else ChildOnBackground
                )
            }

            Spacer(Modifier.height(36.dp))
        }
    }
}

// ── Accessibility guide dialog ────────────────────────────────────────
@Composable
private fun AccessibilityGuideDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = ChildCard)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Accessibility, null, tint = ChildAccent, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text("Enable Accessibility", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = ChildOnBackground, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))

                listOf(
                    "Tap \"Open Settings\" below",
                    "Find and tap \"NW Child Monitor\"",
                    "Toggle the switch to ON",
                    "Tap \"Allow\" on the confirmation",
                    "Come back and tap Refresh"
                ).forEachIndexed { index, step ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(28.dp).clip(CircleShape)
                                .background(ChildAccent.copy(0.2f))
                                .border(1.dp, ChildAccent.copy(0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ChildAccent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(step, fontSize = 13.sp, color = ChildOnBackground)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = CardDefaults.cardColors(containerColor = ChildWarning.copy(0.1f)),
                    border   = BorderStroke(1.dp, ChildWarning.copy(0.4f))
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, null, tint = ChildWarning, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "If you see \"Restricted setting\" — go to Settings → Apps → NW Child → tap ⋮ menu → Allow restricted settings. Then come back.",
                            fontSize = 11.sp, color = ChildWarning, lineHeight = 16.sp
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick  = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = ChildAccent)
                ) {
                    Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open Settings", fontWeight = FontWeight.Bold, color = Color(0xFF003300))
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel", color = ChildOnSurface)
                }
            }
        }
    }
}

// ── Grant dispatcher ──────────────────────────────────────────────────
private fun grantItem(
    item: PermissionItem,
    requestRuntime: (Array<String>) -> Unit,
    openSettings: (String) -> Unit
) {
    when (item) {
        PermissionItem.CAMERA ->
            requestRuntime(arrayOf(android.Manifest.permission.CAMERA))
        PermissionItem.MICROPHONE ->
            requestRuntime(arrayOf(android.Manifest.permission.RECORD_AUDIO))
        PermissionItem.CONTACTS ->
            requestRuntime(arrayOf(android.Manifest.permission.READ_CONTACTS))
        PermissionItem.CALL_LOG ->
            requestRuntime(arrayOf(android.Manifest.permission.READ_CALL_LOG))
        PermissionItem.SMS ->
            requestRuntime(arrayOf(android.Manifest.permission.READ_SMS))
        PermissionItem.LOCATION ->
            requestRuntime(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        PermissionItem.STORAGE -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestRuntime(arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO
                ))
            } else {
                requestRuntime(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }
        PermissionItem.NOTIFICATIONS  -> openSettings("notification_listener")
        PermissionItem.ACCESSIBILITY  -> openSettings("accessibility")
        PermissionItem.USAGE_STATS    -> openSettings("usage_access")
        PermissionItem.OVERLAY        -> openSettings("overlay")
        PermissionItem.SCREEN_SHARE   -> { /* MediaProjection at runtime */ }
    }
}

// ── Permission card ───────────────────────────────────────────────────
@Composable
private fun PermCard(
    item: PermissionItem,
    isGranted: Boolean,
    onGrant: () -> Unit
) {
    val icon: ImageVector = when (item) {
        PermissionItem.CAMERA        -> Icons.Default.Videocam
        PermissionItem.MICROPHONE    -> Icons.Default.Mic
        PermissionItem.SCREEN_SHARE  -> Icons.Default.ScreenShare
        PermissionItem.STORAGE       -> Icons.Default.Folder
        PermissionItem.NOTIFICATIONS -> Icons.Default.Notifications
        PermissionItem.CONTACTS      -> Icons.Default.Contacts
        PermissionItem.ACCESSIBILITY -> Icons.Default.Accessibility
        PermissionItem.USAGE_STATS   -> Icons.Default.BarChart
        PermissionItem.CALL_LOG      -> Icons.Default.Call
        PermissionItem.SMS           -> Icons.Default.Message
        PermissionItem.LOCATION      -> Icons.Default.LocationOn
        PermissionItem.OVERLAY       -> Icons.Default.Layers
    }

    val hint: String? = when {
        !isGranted && item == PermissionItem.ACCESSIBILITY  -> "→ Step-by-step guide provided"
        !isGranted && item.requiresSettings                 -> "→ Opens system settings"
        else                                                -> null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(
            containerColor = if (isGranted) ChildSuccess.copy(0.1f) else ChildCard
        ),
        border = BorderStroke(1.dp, if (isGranted) ChildSuccess.copy(0.4f) else ChildError.copy(0.3f))
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape)
                    .background(if (isGranted) ChildSuccess.copy(0.14f) else ChildError.copy(0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null,
                    tint     = if (isGranted) ChildSuccess else ChildError,
                    modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ChildOnBackground)
                Text(item.description, fontSize = 11.sp, color = ChildOnSurface)
                if (hint != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(hint, fontSize = 10.sp,
                        color = if (item == PermissionItem.ACCESSIBILITY) ChildAccent else ChildWarning)
                }
            }
            if (!isGranted) {
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick        = onGrant,
                    shape          = RoundedCornerShape(10.dp),
                    colors         = ButtonDefaults.buttonColors(containerColor = ChildAccent),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Grant", fontSize = 12.sp, color = Color(0xFF003300), fontWeight = FontWeight.Bold)
                }
            } else {
                Icon(Icons.Default.CheckCircle, null, tint = ChildSuccess, modifier = Modifier.size(26.dp))
            }
        }
    }
}