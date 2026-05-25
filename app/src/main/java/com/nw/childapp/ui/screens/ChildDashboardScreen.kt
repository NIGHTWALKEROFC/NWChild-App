// PATH: app/src/main/java/com/nw/childapp/ui/screens/ChildDashboardScreen.kt
package com.nw.childapp.ui.screens

import androidx.compose.animation.*
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
import com.nw.childapp.ui.theme.*
import com.nw.childapp.viewmodel.ChildViewModel
import kotlinx.coroutines.delay

@Composable
fun ChildDashboardScreen(
    viewModel: ChildViewModel,
    onDisconnected: () -> Unit,
    onPermissionsMissing: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    var showDisconnectDialog    by remember { mutableStateOf(false) }
    var showDeleteDialog        by remember { mutableStateOf(false) }
    // Permission removal intercept
    var showPermissionWarning   by remember { mutableStateOf(false) }
    var permissionWarningMsg    by remember { mutableStateOf("") }

    // Navigate away if disconnected
    LaunchedEffect(uiState.isPaired) {
        if (!uiState.isPaired) onDisconnected()
    }

    // Instead of navigating away when permission drops — show warning and request parent
    LaunchedEffect(uiState.allPermissionsGranted) {
        if (!uiState.allPermissionsGranted && uiState.isPaired) {
            // Show warning — don't auto navigate, let child see the warning first
            permissionWarningMsg = "A permission was disabled. Parent has been notified. Please re-enable it."
            showPermissionWarning = true
        }
    }

    LaunchedEffect(uiState.successMessage) {
        if (uiState.successMessage != null) { delay(2500); viewModel.clearSuccess() }
    }

    // ── Dialogs ───────────────────────────────────────────────────────

    // Permission removed warning — stay in app, show re-enable prompt
    if (showPermissionWarning) {
        AlertDialog(
            onDismissRequest = {},
            containerColor   = ChildCard,
            icon  = { Icon(Icons.Default.Warning, null, tint = ChildWarning) },
            title = { Text("Permission Disabled", color = ChildOnBackground, fontWeight = FontWeight.Bold) },
            text  = {
                Text(
                    "A required permission was turned off.\nYour parent has been notified.\n\nPlease go to Settings and re-enable the permission to continue using this device.",
                    color       = ChildOnSurface,
                    textAlign   = TextAlign.Center,
                    lineHeight  = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionWarning = false
                        onPermissionsMissing()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ChildAccent)
                ) { Text("Re-enable Permissions", color = Color(0xFF003300), fontWeight = FontWeight.Bold) }
            }
        )
    }

    // Disconnect request denied
    if (uiState.disconnectDenied) {
        AlertDialog(
            onDismissRequest = { viewModel.clearDisconnectDenied() },
            containerColor   = ChildCard,
            icon  = { Icon(Icons.Default.Block, null, tint = ChildError) },
            title = { Text("Request Denied", color = ChildOnBackground, fontWeight = FontWeight.Bold) },
            text  = { Text("Your parent denied the disconnect request.", color = ChildOnSurface, textAlign = TextAlign.Center) },
            confirmButton = {
                Button(onClick = { viewModel.clearDisconnectDenied() },
                    colors = ButtonDefaults.buttonColors(containerColor = ChildAccent)
                ) { Text("OK", color = Color(0xFF003300)) }
            }
        )
    }

    // Delete denied
    if (uiState.deleteDenied) {
        AlertDialog(
            onDismissRequest = { viewModel.clearDeleteDenied() },
            containerColor   = ChildCard,
            icon  = { Icon(Icons.Default.Block, null, tint = ChildError) },
            title = { Text("Delete Denied", color = ChildOnBackground, fontWeight = FontWeight.Bold) },
            text  = { Text("Your parent denied the app deletion request.", color = ChildOnSurface, textAlign = TextAlign.Center) },
            confirmButton = {
                Button(onClick = { viewModel.clearDeleteDenied() },
                    colors = ButtonDefaults.buttonColors(containerColor = ChildAccent)
                ) { Text("OK", color = Color(0xFF003300)) }
            }
        )
    }

    // Update dialog
    uiState.updateInfo?.let { upd ->
        AlertDialog(
            onDismissRequest = { if (!upd.mandatory) viewModel.dismissUpdate() },
            containerColor   = ChildCard,
            title = { Text("Update Available", color = ChildOnBackground, fontWeight = FontWeight.Bold) },
            text  = {
                Column {
                    Text("Version ${upd.versionName} is ready.", color = ChildOnSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(upd.releaseNotes, color = ChildOnSurface, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissUpdate() },
                    colors = ButtonDefaults.buttonColors(containerColor = ChildAccent)
                ) { Text("Update", color = Color(0xFF003300)) }
            },
            dismissButton = if (!upd.mandatory) {
                { TextButton(onClick = { viewModel.dismissUpdate() }) { Text("Later", color = ChildOnSurface) } }
            } else null
        )
    }

    // ── Main UI ───────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ChildBackground, Color(0xFF040D04))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(52.dp))

            // Header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(46.dp).clip(CircleShape)
                        .background(ChildAccent.copy(0.15f))
                        .border(1.5.dp, ChildAccent.copy(0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.ChildCare, null, tint = ChildAccent, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("NW Child", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = ChildOnBackground)
                    Text(uiState.deviceName, fontSize = 12.sp, color = ChildOnSurface)
                }
                // Connected badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(ChildSuccess.copy(0.14f))
                        .border(1.dp, ChildSuccess.copy(0.4f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(ChildSuccess))
                        Spacer(Modifier.width(5.dp))
                        Text("Connected", fontSize = 11.sp, color = ChildSuccess, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Active monitoring banner
            if (uiState.cameraActive || uiState.micActive || uiState.screenShareActive) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = CardDefaults.cardColors(containerColor = ChildWarning.copy(0.11f)),
                    border   = BorderStroke(1.dp, ChildWarning.copy(0.5f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Visibility, null, tint = ChildWarning, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Parent is monitoring", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ChildWarning)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (uiState.cameraActive)      ActiveChip("Camera",    Icons.Default.Videocam)
                            if (uiState.micActive)         ActiveChip("Mic",       Icons.Default.Mic)
                            if (uiState.screenShareActive) ActiveChip("Screen",    Icons.Default.ScreenShare)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // Pending disconnect spinner
            if (uiState.pendingDisconnect) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = CardDefaults.cardColors(containerColor = ChildWarning.copy(0.1f)),
                    border   = BorderStroke(1.dp, ChildWarning.copy(0.4f))
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = ChildWarning, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Waiting for parent approval…", fontSize = 13.sp, color = ChildWarning)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // Permissions overview
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(20.dp),
                colors   = CardDefaults.cardColors(containerColor = ChildCard)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = ChildAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("PERMISSIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = ChildAccent, letterSpacing = 2.sp)
                        Spacer(Modifier.weight(1f))
                        // Overall status badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (uiState.allPermissionsGranted) ChildSuccess.copy(0.15f)
                                    else ChildError.copy(0.15f)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                if (uiState.allPermissionsGranted) "All Active" else "Action Needed",
                                fontSize   = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color      = if (uiState.allPermissionsGranted) ChildSuccess else ChildError
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val p = uiState.permissions
                    PermRow("Camera",           Icons.Default.Videocam,     p.camera)
                    PermRow("Microphone",       Icons.Default.Mic,          p.microphone)
                    PermRow("Screen Share",     Icons.Default.ScreenShare,  p.screenShare)
                    PermRow("Storage",          Icons.Default.Folder,       p.storage)
                    PermRow("Notifications",    Icons.Default.Notifications, p.notifications)
                    PermRow("Contacts",         Icons.Default.Contacts,     p.contacts)
                    PermRow("Accessibility",    Icons.Default.Accessibility,p.accessibility)
                    PermRow("App Usage Stats",  Icons.Default.BarChart,     p.usageStats, isLast = true)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Device info
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(16.dp),
                colors   = CardDefaults.cardColors(containerColor = ChildCard)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Device Info", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ChildOnBackground)
                    Spacer(Modifier.height(10.dp))
                    InfoRow("Device",   uiState.deviceName)
                    InfoRow("Status",   "Protected & connected")
                    InfoRow("Device ID", uiState.deviceId.take(14) + "…")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Important notice about permissions
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(14.dp),
                colors   = CardDefaults.cardColors(containerColor = ChildWarning.copy(0.08f)),
                border   = BorderStroke(1.dp, ChildWarning.copy(0.3f))
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = ChildWarning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Do not disable any permissions. If you want to disconnect or delete this app, you must get approval from your parent first.",
                        fontSize   = 12.sp,
                        color      = ChildWarning,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Disconnect / Delete card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(16.dp),
                colors   = CardDefaults.cardColors(containerColor = ChildError.copy(0.08f)),
                border   = BorderStroke(1.dp, ChildError.copy(0.25f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Manage Connection", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ChildError)
                    Spacer(Modifier.height(4.dp))
                    Text("Both actions require parent approval.", fontSize = 11.sp, color = ChildOnSurface)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick  = { showDisconnectDialog = true },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(10.dp),
                            border   = BorderStroke(1.dp, ChildWarning),
                            enabled  = !uiState.pendingDisconnect
                        ) {
                            Icon(Icons.Default.LinkOff, null, tint = ChildWarning, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Disconnect", color = ChildWarning, fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick  = { showDeleteDialog = true },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(10.dp),
                            border   = BorderStroke(1.dp, ChildError)
                        ) {
                            Icon(Icons.Default.DeleteForever, null, tint = ChildError, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete App", color = ChildError, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(36.dp))
        }

        // Disconnect confirm
        if (showDisconnectDialog) {
            AlertDialog(
                onDismissRequest = { showDisconnectDialog = false },
                containerColor   = ChildCard,
                icon  = { Icon(Icons.Default.LinkOff, null, tint = ChildWarning) },
                title = { Text("Request Disconnect?", color = ChildOnBackground, fontWeight = FontWeight.Bold) },
                text  = {
                    Text(
                        "A request will be sent to your parent.\nDisconnecting requires their approval.",
                        color = ChildOnSurface, textAlign = TextAlign.Center
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { showDisconnectDialog = false; viewModel.requestDisconnect() },
                        colors  = ButtonDefaults.buttonColors(containerColor = ChildWarning)
                    ) { Text("Send Request") }
                },
                dismissButton = {
                    TextButton(onClick = { showDisconnectDialog = false }) {
                        Text("Cancel", color = ChildOnSurface)
                    }
                }
            )
        }

        // Delete confirm
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                containerColor   = ChildCard,
                icon  = { Icon(Icons.Default.DeleteForever, null, tint = ChildError) },
                title = { Text("Request App Deletion?", color = ChildOnBackground, fontWeight = FontWeight.Bold) },
                text  = {
                    Text(
                        "A request will be sent to your parent.\nThe app can only be deleted after their approval.",
                        color = ChildOnSurface, textAlign = TextAlign.Center
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { showDeleteDialog = false; viewModel.requestDelete() },
                        colors  = ButtonDefaults.buttonColors(containerColor = ChildError)
                    ) { Text("Send Request") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel", color = ChildOnSurface)
                    }
                }
            )
        }

        // Success toast
        AnimatedVisibility(
            visible  = uiState.successMessage != null,
            enter    = slideInVertically { it } + fadeIn(),
            exit     = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
        ) {
            Card(
                shape  = RoundedCornerShape(50.dp),
                colors = CardDefaults.cardColors(containerColor = ChildSuccess)
            ) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(uiState.successMessage ?: "", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ActiveChip(label: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ChildWarning.copy(0.15f))
            .border(1.dp, ChildWarning.copy(0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = ChildWarning, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = ChildWarning, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PermRow(label: String, icon: ImageVector, granted: Boolean, isLast: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null,
            tint = if (granted) ChildSuccess else ChildError,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = ChildOnBackground, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (granted) ChildSuccess.copy(0.15f) else ChildError.copy(0.15f))
                .padding(horizontal = 9.dp, vertical = 3.dp)
        ) {
            Text(
                if (granted) "ON" else "OFF",
                fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                color = if (granted) ChildSuccess else ChildError
            )
        }
    }
    if (!isLast) Divider(color = ChildSurface, thickness = 1.dp)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = ChildOnSurface)
        Text(value, fontSize = 13.sp, color = ChildOnBackground, fontWeight = FontWeight.Medium)
    }
}