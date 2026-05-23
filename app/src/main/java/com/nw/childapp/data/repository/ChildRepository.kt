// PATH: nw-child-app/app/src/main/java/com/nw/childapp/data/repository/ChildRepository.kt
package com.nw.childapp.data.repository

import com.google.firebase.database.*
import com.nw.childapp.data.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await

class ChildRepository {

    private val db          = FirebaseDatabase.getInstance()
    private val pairingRef  = db.getReference("pairing")
    private val devicesRef  = db.getReference("devices")
    private val commandsRef = db.getReference("commands")
    private val requestsRef = db.getReference("requests")

    // ── Pairing ──────────────────────────────────────────────────────

    /**
     * Validate the 6-digit code, then register this device under the parent.
     * Returns the parentDeviceId on success.
     */
    suspend fun verifyAndPair(
        code: String,
        childDeviceId: String,
        deviceName: String,
        fcmToken: String
    ): Result<String> = try {
        val snap = pairingRef.child(code).get().await()

        if (!snap.exists())
            return Result.failure(Exception("Invalid pairing code. Check the code and try again."))

        val used = snap.child("used").getValue(Boolean::class.java) ?: false
        if (used)
            return Result.failure(Exception("This code has already been used. Ask parent to generate a new one."))

        val expiresAt = snap.child("expiresAt").getValue(Long::class.java) ?: 0L
        if (System.currentTimeMillis() > expiresAt)
            return Result.failure(Exception("Code has expired. Ask parent to generate a new one."))

        val parentDeviceId = snap.child("parentDeviceId").getValue(String::class.java)
            ?: return Result.failure(Exception("Malformed pairing data. Please try again."))

        // Register child device in Firebase
        val device = ChildDevice(
            deviceId       = childDeviceId,
            deviceName     = deviceName,
            connectedAt    = System.currentTimeMillis(),
            parentDeviceId = parentDeviceId,
            fcmToken       = fcmToken,
            isOnline       = true,
            isConnected    = true
        )
        devicesRef.child(childDeviceId).setValue(device).await()

        // Mark code as used so parent listener fires
        pairingRef.child(code).updateChildren(
            mapOf("used" to true, "childDeviceId" to childDeviceId)
        ).await()

        Result.success(parentDeviceId)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ── Permissions & Status ─────────────────────────────────────────

    /** Push current permission state up to Firebase so parent sees it live */
    suspend fun updatePermissions(deviceId: String, permissions: ChildPermissions) {
        devicesRef.child(deviceId).child("permissions").setValue(permissions).await()
    }

    /** Update online/offline status and last-seen timestamp */
    suspend fun updateOnlineStatus(deviceId: String, isOnline: Boolean) {
        devicesRef.child(deviceId).updateChildren(
            mapOf("isOnline" to isOnline, "lastSeen" to System.currentTimeMillis())
        ).await()
    }

    /** Mark device as fully disconnected */
    suspend fun markDisconnected(deviceId: String) {
        devicesRef.child(deviceId).updateChildren(
            mapOf("isConnected" to false, "isOnline" to false)
        ).await()
    }

    // ── Command Listener ─────────────────────────────────────────────

    /**
     * Emits each command the parent pushes into commands/{deviceId}.
     * Each command is deleted from Firebase immediately after being emitted
     * so it is processed exactly once.
     */
    fun listenForCommands(deviceId: String): Flow<ControlCommand?> = callbackFlow {
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val cmd = snapshot.getValue(ControlCommand::class.java) ?: return
                // Ignore expired commands
                if (cmd.expiresAt > 0L && System.currentTimeMillis() > cmd.expiresAt) {
                    snapshot.ref.removeValue()
                    return
                }
                trySend(cmd)
                snapshot.ref.removeValue()   // consume — do not re-process
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) = close(error.toException())
        }
        commandsRef.child(deviceId).addChildEventListener(listener)
        awaitClose { commandsRef.child(deviceId).removeEventListener(listener) }
    }

    // ── Requests (child → parent) ────────────────────────────────────

    /** Tell parent the child wants to disconnect */
    suspend fun requestDisconnect(deviceId: String) {
        requestsRef.child(deviceId).updateChildren(
            mapOf(
                "disconnect_request"   to true,
                "disconnect_timestamp" to System.currentTimeMillis()
            )
        ).await()
    }

    /** Tell parent the child wants to delete the app */
    suspend fun requestDelete(deviceId: String) {
        requestsRef.child(deviceId).updateChildren(
            mapOf(
                "delete_request"   to true,
                "delete_timestamp" to System.currentTimeMillis()
            )
        ).await()
    }

    /** Clear disconnect request node after it is resolved */
    suspend fun clearDisconnectRequest(deviceId: String) {
        requestsRef.child(deviceId).child("disconnect_request").removeValue().await()
    }
}