// PATH: nw-child-app/app/src/main/java/com/nw/childapp/service/ScreenCaptureService.kt
package com.nw.childapp.service

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/**
 * Screen capture service using MediaProjection API.
 * - Captures a JPEG thumbnail every 3 seconds
 * - Uploads Base64-encoded frame to Firebase Realtime Database
 * - Parent app reads the latest frame and displays it
 * Must be started with a valid MediaProjection result code and data Intent.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private val db    by lazy { FirebaseDatabase.getInstance() }
    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay:  VirtualDisplay?  = null
    private var imageReader:     ImageReader?     = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(10, buildNotification())

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (code != 0 && data != null) startCapture(code, data)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val wm      = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // Capture at ¼ resolution to keep Firebase bandwidth low
        val width  = metrics.widthPixels  / 4
        val height = metrics.heightPixels / 4

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "NWChildScreen", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        scope.launch {
            while (isActive) {
                delay(3_000)
                uploadFrame(width, height)
            }
        }
    }

    private fun uploadFrame(width: Int, height: Int) {
        val deviceId = prefs.getString("device_id", null) ?: return
        val image    = imageReader?.acquireLatestImage() ?: return
        try {
            val plane      = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val bmp        = Bitmap.createBitmap(
                width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)

            val out    = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 25, out)
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            bmp.recycle()

            db.getReference("screen_frames").child(deviceId).setValue(
                mapOf("frame" to base64, "timestamp" to ServerValue.TIMESTAMP)
            )
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "nw_screen"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Screen Share", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NW Child – Screen Sharing")
            .setContentText("Your parent is viewing this screen")
            .setOngoing(true)
            .build()
    }
}