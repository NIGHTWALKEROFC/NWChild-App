// PATH: nw-child-app/app/src/main/java/com/nw/childapp/viewmodel/ChildViewModel.kt
package com.nw.childapp.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.nw.childapp.data.*
import com.nw.childapp.data.repository.ChildRepository
import com.nw.childapp.data.repository.UpdateRepository
import com.nw.childapp.service.ChildMonitorService
import com.nw.childapp.util.PermissionHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class ChildUiState(
    val isPaired: Boolean           = false,
    val isConnected: Boolean        = false,
    val deviceId: String            = "",
    val deviceName: String          = "",
    val parentDeviceId: String      = "",
    val permissions: ChildPermissions = ChildPermissions(),
    val allPermissionsGranted: Boolean = false,
    val isLoading: Boolean          = false,
    val errorMessage: String?       = null,
    val successMessage: String?     = null,
    val updateInfo: UpdateInfo?     = null,
    val pendingDisconnect: Boolean  = false,
    val disconnectDenied: Boolean   = false,
    val deleteDenied: Boolean       = false,
    val cameraActive: Boolean       = false,
    val micActive: Boolean          = false,
    val screenShareActive: Boolean  = false
)

class ChildViewModel(application: Application) : AndroidViewModel(application) {

    private val repo       = ChildRepository()
    private val updateRepo = UpdateRepository(application)
    private val ctx        = application.applicationContext
    private val prefs      = application.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(ChildUiState())
    val uiState: StateFlow<ChildUiState> = _uiState.asStateFlow()

    private var commandListenerJob: Job? = null
    private var permissionPollJob:  Job? = null

    val deviceId: String by lazy {
        prefs.getString("device_id", null) ?: run {
            val id = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                ?: java.util.UUID.randomUUID().toString())
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }

    val deviceName: String by lazy { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }

    init {
        // Anonymous sign-in so Firebase security rules allow access
        FirebaseAuth.getInstance().signInAnonymously()

        // Restore state if already paired
        if (prefs.getBoolean("is_paired", false)) {
            val parentId = prefs.getString("parent_device_id", "") ?: ""
            _uiState.update {
                it.copy(
                    isPaired       = true,
                    isConnected    = true,
                    deviceId       = deviceId,
                    deviceName     = deviceName,
                    parentDeviceId = parentId
                )
            }
            refreshPermissions()
            startCommandListener()
            startMonitorService()
        }
        checkForUpdates()
    }

    // ── Permissions ───────────────────────────────────────────────────

    fun refreshPermissions() {
        val perms      = PermissionHelper.checkAllPermissions(ctx)
        val allGranted = perms.allGranted()
        _uiState.update { it.copy(permissions = perms, allPermissionsGranted = allGranted) }
        if (_uiState.value.isPaired) {
            viewModelScope.launch {
                try { repo.updatePermissions(deviceId, perms) } catch (_: Exception) {}
            }
        }
    }

    // ── Pairing ───────────────────────────────────────────────────────

    fun submitPairingCode(code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val fcmToken = try {
                    FirebaseMessaging.getInstance().token.await()
                } catch (_: Exception) { "" }

                val result = repo.verifyAndPair(code.trim(), deviceId, deviceName, fcmToken)

                result.fold(
                    onSuccess = { parentId ->
                        prefs.edit()
                            .putBoolean("is_paired", true)
                            .putString("parent_device_id", parentId)
                            .apply()

                        _uiState.update {
                            it.copy(
                                isPaired       = true,
                                isConnected    = true,
                                deviceId       = deviceId,
                                deviceName     = deviceName,
                                parentDeviceId = parentId,
                                isLoading      = false,
                                successMessage = "Paired successfully!"
                            )
                        }
                        refreshPermissions()
                        startCommandListener()
                        startMonitorService()
                    },
                    onFailure = { e ->
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = e.message ?: "Pairing failed")
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Error: ${e.message}") }
            }
        }
    }

    // ── Disconnect / Delete requests ──────────────────────────────────

    fun requestDisconnect() {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingDisconnect = true) }
            try { repo.requestDisconnect(deviceId) }
            catch (e: Exception) {
                _uiState.update { it.copy(pendingDisconnect = false, errorMessage = "Request failed") }
            }
        }
    }

    fun requestDelete() {
        viewModelScope.launch {
            try { repo.requestDelete(deviceId) }
            catch (e: Exception) { _uiState.update { it.copy(errorMessage = "Request failed") } }
        }
    }

    // ── Command handling ──────────────────────────────────────────────

    private fun startCommandListener() {
        commandListenerJob?.cancel()
        commandListenerJob = viewModelScope.launch {
            repo.listenForCommands(deviceId).collect { cmd ->
                cmd ?: return@collect
                handleCommand(cmd)
            }
        }

        // Poll permissions every 5 s and report to Firebase
        permissionPollJob?.cancel()
        permissionPollJob = viewModelScope.launch {
            while (true) {
                delay(5_000)
                refreshPermissions()
            }
        }

        // Mark online
        viewModelScope.launch {
            try { repo.updateOnlineStatus(deviceId, true) } catch (_: Exception) {}
        }
    }

    private fun handleCommand(cmd: ControlCommand) {
        when (cmd.type) {
            CommandTypes.ENABLE_CAMERA      -> _uiState.update { it.copy(cameraActive = true) }
            CommandTypes.DISABLE_CAMERA     -> _uiState.update { it.copy(cameraActive = false) }
            CommandTypes.ENABLE_MIC         -> _uiState.update { it.copy(micActive = true) }
            CommandTypes.DISABLE_MIC        -> _uiState.update { it.copy(micActive = false) }
            CommandTypes.START_SCREEN_SHARE -> _uiState.update { it.copy(screenShareActive = true) }
            CommandTypes.STOP_SCREEN_SHARE  -> _uiState.update { it.copy(screenShareActive = false) }

            CommandTypes.BLOCK_APP -> {
                val blocked = prefs.getStringSet("blocked_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                blocked.add(cmd.value)
                prefs.edit().putStringSet("blocked_apps", blocked).apply()
            }
            CommandTypes.UNBLOCK_APP -> {
                val blocked = prefs.getStringSet("blocked_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                blocked.remove(cmd.value)
                prefs.edit().putStringSet("blocked_apps", blocked).apply()
            }
            CommandTypes.SET_APP_LIMIT -> {
                val parts = cmd.value.split(":")
                if (parts.size == 2) {
                    val limits = prefs.getString("app_limits", "{}") ?: "{}"
                    val entry  = "\"${parts[0]}\":${parts[1]}"
                    val updated = if (limits == "{}") "{$entry}" else limits.replace("}", ",$entry}")
                    prefs.edit().putString("app_limits", updated).apply()
                }
            }

            CommandTypes.APPROVE_DISCONNECT,
            CommandTypes.FORCE_DISCONNECT -> performDisconnect()

            CommandTypes.DENY_DISCONNECT ->
                _uiState.update { it.copy(disconnectDenied = true, pendingDisconnect = false) }

            CommandTypes.APPROVE_DELETE -> {
                // Notify UI — user should then confirm uninstall
                _uiState.update { it.copy(successMessage = "Delete approved by parent") }
                ctx.sendBroadcast(Intent("com.nw.childapp.APPROVE_DELETE"))
            }
            CommandTypes.DENY_DELETE ->
                _uiState.update { it.copy(deleteDenied = true) }
        }
    }

    private fun performDisconnect() {
        viewModelScope.launch {
            try { repo.markDisconnected(deviceId) } catch (_: Exception) {}
        }
        prefs.edit().putBoolean("is_paired", false).remove("parent_device_id").apply()
        commandListenerJob?.cancel()
        permissionPollJob?.cancel()
        ctx.stopService(Intent(ctx, ChildMonitorService::class.java))
        _uiState.update { ChildUiState() } // reset all state
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun startMonitorService() {
        try {
            ctx.startForegroundService(Intent(ctx, ChildMonitorService::class.java))
        } catch (_: Exception) {}
    }

    private fun checkForUpdates() {
        viewModelScope.launch {
            val upd = updateRepo.checkForUpdates(currentVersionCode = 1)
            if (upd != null) _uiState.update { it.copy(updateInfo = upd) }
        }
    }

    fun clearError()          = _uiState.update { it.copy(errorMessage = null) }
    fun clearSuccess()        = _uiState.update { it.copy(successMessage = null) }
    fun dismissUpdate()       = _uiState.update { it.copy(updateInfo = null) }
    fun clearDisconnectDenied() = _uiState.update { it.copy(disconnectDenied = false) }
    fun clearDeleteDenied()   = _uiState.update { it.copy(deleteDenied = false) }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            try { repo.updateOnlineStatus(deviceId, false) } catch (_: Exception) {}
        }
    }
}