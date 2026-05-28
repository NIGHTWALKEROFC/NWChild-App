// PATH: app/src/main/java/com/nw/childapp/service/DataSyncHelper.kt
package com.nw.childapp.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

object DataSyncHelper {

    private val db by lazy { FirebaseDatabase.getInstance() }

    // ── Contacts ──────────────────────────────────────────────────────
    fun syncContacts(context: Context, deviceId: String, scope: CoroutineScope) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return

        scope.launch(Dispatchers.IO) {
            try {
                val seen  = mutableSetOf<String>()
                val batch = mutableMapOf<String, Any>()
                var index = 0

                val cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )

                cursor?.use { c ->
                    val nameIdx   = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val name   = (c.getString(nameIdx)   ?: "Unknown").trim()
                        val number = (c.getString(numberIdx) ?: "").trim()
                        val key    = "$name|$number"
                        if (key !in seen) {
                            seen.add(key)
                            batch["c$index"] = mapOf("name" to name, "number" to number)
                            index++
                        }
                    }
                }

                if (batch.isNotEmpty()) {
                    val ref = db.getReference("contacts").child(deviceId)
                    ref.removeValue().addOnCompleteListener {
                        ref.updateChildren(batch)
                        Log.d("DataSync", "Synced $index contacts")
                    }
                }
            } catch (e: Exception) {
                Log.e("DataSync", "Contacts error: ${e.message}")
            }
        }
    }

    // ── Call Log ──────────────────────────────────────────────────────
    fun syncCallLog(context: Context, deviceId: String, scope: CoroutineScope) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return

        scope.launch(Dispatchers.IO) {
            try {
                val cursor = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DURATION,
                        CallLog.Calls.DATE
                    ),
                    null, null,
                    "${CallLog.Calls.DATE} DESC LIMIT 200"
                )

                val batch = mutableMapOf<String, Any>()
                var index = 0

                cursor?.use { c ->
                    val numIdx  = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                    val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val durIdx  = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)

                    while (c.moveToNext()) {
                        val type = when (c.getInt(typeIdx)) {
                            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                            CallLog.Calls.MISSED_TYPE   -> "MISSED"
                            else -> "UNKNOWN"
                        }
                        batch["call_$index"] = mapOf(
                            "number"    to (c.getString(numIdx)  ?: ""),
                            "name"      to (c.getString(nameIdx) ?: ""),
                            "type"      to type,
                            "duration"  to c.getLong(durIdx),
                            "timestamp" to c.getLong(dateIdx)
                        )
                        index++
                    }
                }

                if (batch.isNotEmpty()) {
                    db.getReference("call_log").child(deviceId).setValue(batch)
                    Log.d("DataSync", "Synced $index call logs")
                }
            } catch (e: Exception) {
                Log.e("DataSync", "Call log error: ${e.message}")
            }
        }
    }

    // ── SMS ───────────────────────────────────────────────────────────
    fun syncSms(context: Context, deviceId: String, scope: CoroutineScope) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) return

        scope.launch(Dispatchers.IO) {
            try {
                val batch = mutableMapOf<String, Any>()
                var index = 0

                // Inbox
                index = syncSmsBox(context, Telephony.Sms.Inbox.CONTENT_URI, "INBOX", batch, index)
                // Sent
                syncSmsBox(context, Telephony.Sms.Sent.CONTENT_URI, "SENT", batch, index)

                if (batch.isNotEmpty()) {
                    db.getReference("sms").child(deviceId).setValue(batch)
                    Log.d("DataSync", "Synced ${batch.size} SMS")
                }
            } catch (e: Exception) {
                Log.e("DataSync", "SMS error: ${e.message}")
            }
        }
    }

    private fun syncSmsBox(
        context: Context,
        uri: android.net.Uri,
        type: String,
        batch: MutableMap<String, Any>,
        startIndex: Int
    ): Int {
        var index = startIndex
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("address", "body", "date"),
                null, null,
                "date DESC LIMIT 100"
            )
            cursor?.use { c ->
                val addrIdx = c.getColumnIndexOrThrow("address")
                val bodyIdx = c.getColumnIndexOrThrow("body")
                val dateIdx = c.getColumnIndexOrThrow("date")
                while (c.moveToNext()) {
                    batch["sms_$index"] = mapOf(
                        "number"    to (c.getString(addrIdx) ?: ""),
                        "body"      to (c.getString(bodyIdx) ?: ""),
                        "type"      to type,
                        "timestamp" to c.getLong(dateIdx)
                    )
                    index++
                }
            }
        } catch (e: Exception) {
            Log.e("DataSync", "SMS box error: ${e.message}")
        }
        return index
    }

    // ── App Usage ─────────────────────────────────────────────────────
    fun syncAppUsage(context: Context, deviceId: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as android.app.usage.UsageStatsManager
                val cal = Calendar.getInstance()
                val endTime   = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val startTime = cal.timeInMillis

                val stats = usm.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    startTime, endTime
                )

                val batch = mutableMapOf<String, Any>()
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                var index = 0
                val pm = context.packageManager

                for (stat in stats) {
                    if (stat.totalTimeInForeground <= 0) continue
                    if (stat.packageName == context.packageName) continue
                    val mins = (stat.totalTimeInForeground / 60000).toInt()
                    if (mins < 1) continue
                    val appName = try {
                        pm.getApplicationLabel(
                            pm.getApplicationInfo(stat.packageName, 0)
                        ).toString()
                    } catch (e: Exception) { stat.packageName }
                    batch["u$index"] = mapOf(
                        "packageName"  to stat.packageName,
                        "appName"      to appName,
                        "usageMinutes" to mins,
                        "date"         to today
                    )
                    index++
                }

                if (batch.isNotEmpty()) {
                    db.getReference("app_usage").child(deviceId).child(today).setValue(batch)
                    Log.d("DataSync", "Synced $index app usage records")
                }
            } catch (e: Exception) {
                Log.e("DataSync", "App usage error: ${e.message}")
            }
        }
    }

    // ── Screenshot upload ─────────────────────────────────────────────
    fun uploadScreenshot(bitmap: Bitmap, deviceId: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                bitmap.recycle()
                db.getReference("screenshots").child(deviceId).setValue(
                    mapOf("frame" to base64, "timestamp" to ServerValue.TIMESTAMP)
                )
                Log.d("DataSync", "Screenshot uploaded")
            } catch (e: Exception) {
                Log.e("DataSync", "Screenshot error: ${e.message}")
            }
        }
    }

    // ── Installed apps sync ───────────────────────────────────────────
    fun syncInstalledApps(context: Context, deviceId: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val pm   = context.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val batch = mutableMapOf<String, Any>()
                var index = 0

                for (app in apps) {
                    if (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) continue
                    if (app.packageName == context.packageName) continue
                    val name = pm.getApplicationLabel(app).toString()
                    batch["app_$index"] = mapOf(
                        "packageName" to app.packageName,
                        "appName"     to name
                    )
                    index++
                }

                if (batch.isNotEmpty()) {
                    db.getReference("installed_apps").child(deviceId).setValue(batch)
                    Log.d("DataSync", "Synced $index installed apps")
                }
            } catch (e: Exception) {
                Log.e("DataSync", "Installed apps error: ${e.message}")
            }
        }
    }
}