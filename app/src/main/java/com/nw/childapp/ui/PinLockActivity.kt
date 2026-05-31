// PATH: app/src/main/java/com/nw/childapp/ui/PinLockActivity.kt
package com.nw.childapp.ui

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nw.childapp.ui.theme.*
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlin.math.roundToInt

class PinLockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        val prefs      = getSharedPreferences("child_prefs", MODE_PRIVATE)
        val correctPin = prefs.getString("parent_pin", "") ?: ""
        val reason     = intent.getStringExtra("reason") ?: "This area is locked by your parent."
        val deviceId   = prefs.getString("device_id", null) ?: ""

        setContent {
            NWChildTheme {
                PinLockScreen(
                    reason     = reason,
                    correctPin = correctPin,
                    onCorrect  = { finish() },
                    onBack     = { setResult(Activity.RESULT_CANCELED); finish() },
                    onWrongAttempts = { count ->
                        if (deviceId.isNotEmpty() && count >= 3) {
                            FirebaseDatabase.getInstance()
                                .getReference("events").child(deviceId).push()
                                .setValue(mapOf(
                                    "type"      to "wrong_pin_attempts",
                                    "count"     to count,
                                    "timestamp" to ServerValue.TIMESTAMP
                                ))
                        }
                    }
                )
            }
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}

@Composable
private fun PinLockScreen(
    reason: String,
    correctPin: String,
    onCorrect: () -> Unit,
    onBack: () -> Unit,
    onWrongAttempts: (Int) -> Unit
) {
    var entered    by remember { mutableStateOf("") }
    var wrongCount by remember { mutableStateOf(0) }
    var errorMsg   by remember { mutableStateOf("") }
    var shaking    by remember { mutableStateOf(false) }

    // Shake animation — uses IntOffset.x so no import conflict
    val shakeAnimatable = remember { Animatable(0f) }

    LaunchedEffect(shaking) {
        if (shaking) {
            repeat(4) {
                shakeAnimatable.animateTo(12f, animationSpec = tween(60))
                shakeAnimatable.animateTo(-12f, animationSpec = tween(60))
            }
            shakeAnimatable.animateTo(0f, animationSpec = tween(60))
            shaking = false
        }
    }

    LaunchedEffect(entered) {
        if (entered.length == 4) {
            if (entered == correctPin || correctPin.isEmpty()) {
                onCorrect()
            } else {
                shaking = true
                wrongCount++
                onWrongAttempts(wrongCount)
                errorMsg = when {
                    wrongCount >= 5 -> "Too many attempts! Parent has been notified."
                    else -> "Wrong PIN. ${5 - wrongCount} attempts left."
                }
                kotlinx.coroutines.delay(700)
                entered = ""
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(ChildBackground, Color(0xFF020802)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier.size(80.dp).clip(CircleShape)
                    .background(ChildError.copy(0.15f))
                    .border(2.dp, ChildError.copy(0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Lock, null, tint = ChildError, modifier = Modifier.size(40.dp)) }

            Spacer(Modifier.height(20.dp))
            Text("Locked by Parent", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = ChildOnBackground)
            Spacer(Modifier.height(8.dp))
            Text(reason, fontSize = 13.sp, color = ChildOnSurface, textAlign = TextAlign.Center, lineHeight = 20.sp)
            Spacer(Modifier.height(36.dp))

            // PIN dots with shake using offset modifier with IntOffset
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.absoluteOffset(x = shakeAnimatable.value.roundToInt().dp)
            ) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier.size(18.dp).clip(CircleShape)
                            .background(if (i < entered.length) ChildAccent else ChildSurface)
                            .border(1.5.dp, ChildAccent.copy(0.5f), CircleShape)
                    )
                }
            }

            if (errorMsg.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(errorMsg, fontSize = 12.sp, color = ChildError, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(36.dp))

            val rows = listOf(
                listOf("1","2","3"),
                listOf("4","5","6"),
                listOf("7","8","9"),
                listOf("","0","⌫")
            )
            rows.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    row.forEach { key ->
                        Box(
                            modifier = Modifier.size(72.dp).clip(CircleShape)
                                .background(if (key.isEmpty()) Color.Transparent else ChildCard)
                                .border(if (key.isEmpty()) 0.dp else 1.dp, ChildAccent.copy(0.2f), CircleShape)
                                .clickable(enabled = key.isNotEmpty()) {
                                    if (key == "⌫") { if (entered.isNotEmpty()) entered = entered.dropLast(1) }
                                    else if (entered.length < 4) entered += key
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (key.isNotEmpty()) {
                                Text(key,
                                    fontSize   = if (key == "⌫") 20.sp else 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color      = ChildOnBackground,
                                    fontFamily = if (key == "⌫") FontFamily.Default else FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onBack) {
                Text("Go Back", color = ChildOnSurface, fontSize = 14.sp)
            }
        }
    }
}