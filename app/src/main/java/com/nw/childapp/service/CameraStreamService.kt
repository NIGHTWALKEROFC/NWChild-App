// PATH: app/src/main/java/com/nw/childapp/service/CameraStreamService.kt
package com.nw.childapp.service

import android.app.*
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.*

class CameraStreamService : Service() {

    private val db    by lazy { FirebaseDatabase.getInstance() }
    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var cameraDevice:   CameraDevice?         = null
    private var captureSession: CameraCaptureSession?  = null
    private var imageReader:    ImageReader?           = null
    private var bgThread:       HandlerThread?         = null
    private var bgHandler:      Handler?               = null

    // Higher quality capture size
    private val captureWidth  = 1280
    private val captureHeight = 720

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(11, buildNotification())
        startBackground()
        openCamera()
        return START_NOT_STICKY
    }

    private fun startBackground() {
        bgThread  = HandlerThread("NWCameraThread").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: run { stopSelf(); return }

            val map = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run { stopSelf(); return }

            // Find best available size close to 720p
            val sizes = map.getOutputSizes(ImageFormat.JPEG)
            val bestSize = sizes.filter { it.width <= captureWidth && it.height <= captureHeight }
                .maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }
                ?: run { stopSelf(); return }

            imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, 3)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val deviceId = prefs.getString("device_id", null) ?: return@setOnImageAvailableListener
                    val buffer   = image.planes[0].buffer
                    val bytes    = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val base64   = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    db.getReference("camera_frames").child(deviceId).setValue(
                        mapOf(
                            "frame"     to base64,
                            "width"     to bestSize.width,
                            "height"    to bestSize.height,
                            "timestamp" to ServerValue.TIMESTAMP
                        )
                    )
                } catch (e: Exception) {
                    Log.e("CameraStream", "Frame error: ${e.message}")
                } finally {
                    image.close()
                }
            }, bgHandler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    Log.e("CameraStream", "Camera error $error"); stopSelf()
                }
            }, bgHandler)

        } catch (e: SecurityException) {
            Log.e("CameraStream", "No permission"); stopSelf()
        } catch (e: Exception) {
            Log.e("CameraStream", "Open failed: ${e.message}"); stopSelf()
        }
    }

    private fun startCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader  ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_QUALITY, 85.toByte()) // Higher quality JPEG
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        scope.launch {
                            while (isActive) {
                                try {
                                    session.capture(builder.build(), null, bgHandler)
                                } catch (e: CameraAccessException) {
                                    Log.e("CameraStream", "Capture error: ${e.message}")
                                }
                                delay(1000) // 1 frame per second — balanced quality vs lag
                            }
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CameraStream", "Session configure failed"); stopSelf()
                    }
                }, bgHandler
            )
        } catch (e: CameraAccessException) {
            Log.e("CameraStream", "Session error: ${e.message}"); stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close()   } catch (_: Exception) {}
        try { imageReader?.close()    } catch (_: Exception) {}
        try { bgThread?.quitSafely()  } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "nw_camera"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Camera Monitoring", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NW Child – Camera Active")
            .setContentText("Your parent is viewing the camera")
            .setOngoing(true)
            .build()
    }
}