// PATH: app/src/main/java/com/nw/childapp/service/MicStreamService.kt
package com.nw.childapp.service

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.*

class MicStreamService : Service() {

    private val db    by lazy { FirebaseDatabase.getInstance() }
    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioRecord: AudioRecord? = null

    // High quality audio settings
    private val sampleRate    = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat   = AudioFormat.ENCODING_PCM_16BIT

    private val minBuffer by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(12, buildNotification())
        startRecording()
        return START_NOT_STICKY
    }

    private fun startRecording() {
        val deviceId = prefs.getString("device_id", null) ?: run { stopSelf(); return }
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, minBuffer
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MicStream", "AudioRecord init failed"); stopSelf(); return
            }
            audioRecord?.startRecording()

            scope.launch {
                val buffer = ByteArray(minBuffer)
                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read > 0) {
                        val chunk  = buffer.copyOf(read)
                        val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                        // Upload chunk — parent reads and plays it
                        db.getReference("audio_chunks").child(deviceId).setValue(
                            mapOf(
                                "chunk"      to base64,
                                "sampleRate" to sampleRate,
                                "encoding"   to "PCM_16BIT",
                                "channels"   to 1,
                                "timestamp"  to ServerValue.TIMESTAMP
                            )
                        )
                    }
                    // Send chunk every 500ms for near-real-time audio
                    delay(500)
                }
            }
        } catch (e: SecurityException) {
            Log.e("MicStream", "Permission denied"); stopSelf()
        } catch (e: Exception) {
            Log.e("MicStream", "Error: ${e.message}"); stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { audioRecord?.stop()    } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "nw_mic"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Microphone Monitoring", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NW Child – Mic Active")
            .setContentText("Your parent is listening")
            .setOngoing(true)
            .build()
    }
}