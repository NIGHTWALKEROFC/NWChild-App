// PATH: app/src/main/java/com/nw/childapp/service/LocationService.kt
package com.nw.childapp.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.*
import java.util.Locale

class LocationService : Service() {

    private val db    by lazy { FirebaseDatabase.getInstance() }
    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(13, buildNotification())
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e("LocationService", "No location permission"); stopSelf(); return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
            .setMinUpdateDistanceMeters(10f)
            .setMinUpdateIntervalMillis(15_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val deviceId = prefs.getString("device_id", null) ?: return
                    scope.launch {
                        val address = try {
                            val geo = Geocoder(applicationContext, Locale.getDefault())
                            @Suppress("DEPRECATION")
                            val addrs = geo.getFromLocation(loc.latitude, loc.longitude, 1)
                            if (!addrs.isNullOrEmpty()) {
                                val a = addrs[0]
                                buildString {
                                    if (!a.thoroughfare.isNullOrEmpty()) append(a.thoroughfare)
                                    if (!a.locality.isNullOrEmpty()) { if (isNotEmpty()) append(", "); append(a.locality) }
                                    if (!a.adminArea.isNullOrEmpty()) { if (isNotEmpty()) append(", "); append(a.adminArea) }
                                }
                            } else ""
                        } catch (e: Exception) { "" }

                        val data = mapOf(
                            "lat"       to loc.latitude,
                            "lng"       to loc.longitude,
                            "accuracy"  to loc.accuracy,
                            "address"   to address,
                            "timestamp" to ServerValue.TIMESTAMP
                        )
                        db.getReference("location").child(deviceId).setValue(data)
                        db.getReference("location_history").child(deviceId).push().setValue(data)
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e("LocationService", "Failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("nw_loc", "Location Tracking", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, "nw_loc")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NW Child – Location Active")
            .setContentText("Location is being monitored")
            .setOngoing(true).build()
    }
}