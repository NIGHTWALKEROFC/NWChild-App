// PATH: app/src/main/java/com/nw/childapp/service/ScreenCaptureService.kt
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
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

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

    // Capture at half screen resolution for better performance
    private var captureWidth  = 720
    private var captureHeight = 1280
    private var screenDensity = 1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(10, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != 0 && data != null) {
            setupDisplayMetrics()
            startCapture(resultCode, data)
        } else {
            Log.e("ScreenCapture", "No projection data"); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setupDisplayMetrics() {
        val wm      = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        // Capture at half resolution to reduce lag while keeping readability
        captureWidth  = metrics.widthPixels / 2
        captureHeight = metrics.heightPixels / 2
        screenDensity = metrics.densityDpi / 2
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)

            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "NWChildScreen",
                captureWidth, captureHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            Log.d("ScreenCapture", "Started capture ${captureWidth}x${captureHeight}")

            scope.launch {
                while (isActive) {
                    uploadFrame()
                    delay(1500) // ~0.7 fps — good balance for screen share
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Start failed: ${e.message}"); stopSelf()
        }
    }

    private fun uploadFrame() {
        val deviceId = prefs.getString("device_id", null) ?: return
        val image    = imageReader?.acquireLatestImage() ?: return
        try {
            val plane      = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * captureWidth
            val bmp = Bitmap.createBitmap(
                captureWidth + rowPadding / plane.pixelStride,
                captureHeight,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)

            // Crop to exact size if needed
            val croppedBmp = if (bmp.width > captureWidth) {
                Bitmap.createBitmap(bmp, 0, 0, captureWidth, captureHeight)
            } else bmp

            val out = ByteArrayOutputStream()
            croppedBmp.compress(Bitmap.CompressFormat.JPEG, 75, out) // 75% quality
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

            if (croppedBmp != bmp) croppedBmp.recycle()
            bmp.recycle()

            db.getReference("screen_frames").child(deviceId).setValue(
                mapOf(
                    "frame"     to base64,
                    "width"     to captureWidth,
                    "height"    to captureHeight,
                    "timestamp" to ServerValue.TIMESTAMP
                )
            )
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Frame error: ${e.message}")
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { virtualDisplay?.release()  } catch (_: Exception) {}
        try { mediaProjection?.stop()    } catch (_: Exception) {}
        try { imageReader?.close()       } catch (_: Exception) {}
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