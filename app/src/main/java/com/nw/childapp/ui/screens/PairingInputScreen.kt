// PATH: app/src/main/java/com/nw/childapp/ui/screens/PairingInputScreen.kt
package com.nw.childapp.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nw.childapp.ui.theme.*
import com.nw.childapp.viewmodel.ChildViewModel
import kotlinx.coroutines.delay

@Composable
fun PairingInputScreen(
    viewModel: ChildViewModel,
    onPaired: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var code by remember { mutableStateOf("") }

    LaunchedEffect(uiState.isPaired) {
        if (uiState.isPaired) onPaired()
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pAlpha by pulse.animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ChildBackground, Color(0xFF040D04), ChildBackground)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(40.dp))

            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(ChildAccent.copy(pAlpha * 0.22f), Color.Transparent)))
                    .border(2.dp, ChildAccent.copy(pAlpha), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ChildCare, null, tint = ChildAccent, modifier = Modifier.size(58.dp))
            }

            Spacer(Modifier.height(26.dp))

            Text("NW Child", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = ChildOnBackground)
            Text("FAMILY SAFETY APP", fontSize = 11.sp, color = ChildOnSurface, letterSpacing = 3.sp)

            Spacer(Modifier.height(48.dp))

            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(24.dp),
                colors    = CardDefaults.cardColors(containerColor = ChildCard),
                elevation = CardDefaults.cardElevation(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VpnKey, null, tint = ChildAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("ENTER PAIRING CODE", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = ChildAccent, letterSpacing = 2.sp)
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        "Get the 6-digit code from the\nNW Parental app on your parent's phone",
                        fontSize = 13.sp, color = ChildOnSurface,
                        textAlign = TextAlign.Center, lineHeight = 20.sp
                    )

                    Spacer(Modifier.height(24.dp))

                    // 6 digit boxes using weight so all fit
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        repeat(6) { index ->
                            if (index == 3) Spacer(Modifier.width(6.dp))
                            val char = code.getOrNull(index)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ChildSurface)
                                    .border(
                                        width = if (code.length == index) 2.dp else 1.dp,
                                        color = if (code.length == index) ChildAccent
                                                else ChildAccent.copy(0.25f),
                                        shape = RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = char?.toString() ?: "",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = ChildOnBackground
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value         = code,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) code = it },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("Type 6-digit code", color = ChildOnSurface) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction    = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (code.length == 6) viewModel.submitPairingCode(code) }
                        ),
                        singleLine = true,
                        colors     = OutlinedTextFieldDefaults.colors(
                            focusedTextColor     = ChildOnBackground,
                            unfocusedTextColor   = ChildOnBackground,
                            focusedBorderColor   = ChildAccent,
                            unfocusedBorderColor = ChildOnSurface.copy(0.4f),
                            cursorColor          = ChildAccent
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick  = { if (code.length == 6) viewModel.submitPairingCode(code) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape    = RoundedCornerShape(14.dp),
                        enabled  = code.length == 6 && !uiState.isLoading,
                        colors   = ButtonDefaults.buttonColors(
                            containerColor         = ChildAccent,
                            contentColor           = Color(0xFF003300),
                            disabledContainerColor = ChildSurface,
                            disabledContentColor   = ChildOnSurface
                        )
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                color       = Color(0xFF003300),
                                modifier    = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(Icons.Default.Link, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Connect Device", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = uiState.errorMessage != null) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Card(
                        shape  = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = ChildError.copy(0.13f)),
                        border = BorderStroke(1.dp, ChildError.copy(0.4f))
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, tint = ChildError, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(uiState.errorMessage ?: "", color = ChildError, fontSize = 13.sp)
                        }
                    }
                }
            }

            LaunchedEffect(uiState.errorMessage) {
                if (uiState.errorMessage != null) {
                    delay(4000); viewModel.clearError()
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}