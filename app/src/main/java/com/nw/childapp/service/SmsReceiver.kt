// PATH: app/src/main/java/com/nw/childapp/service/SmsReceiver.kt
package com.nw.childapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs    = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return
        if (!prefs.getBoolean("is_paired", false)) return
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val db = FirebaseDatabase.getInstance()
            for (msg in messages) {
                db.getReference("sms").child(deviceId).push().setValue(
                    mapOf(
                        "number"    to (msg.originatingAddress ?: ""),
                        "body"      to msg.messageBody,
                        "type"      to "INBOX",
                        "timestamp" to ServerValue.TIMESTAMP
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error: ${e.message}")
        }
    }
}