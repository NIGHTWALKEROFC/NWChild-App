// PATH: nw-child-app/app/src/main/java/com/nw/childapp/ui/theme/Theme.kt
package com.nw.childapp.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ChildAccent       = Color(0xFF69F0AE)
val ChildBackground   = Color(0xFF0D1F0E)
val ChildSurface      = Color(0xFF132B14)
val ChildCard         = Color(0xFF1A3A1B)
val ChildOnBackground = Color(0xFFE8F5E9)
val ChildOnSurface    = Color(0xFFA5D6A7)
val ChildSuccess      = Color(0xFF4CAF50)
val ChildError        = Color(0xFFF44336)
val ChildWarning      = Color(0xFFFF9800)

private val DarkColorScheme = darkColorScheme(
    primary      = ChildAccent,
    secondary    = Color(0xFF388E3C),
    background   = ChildBackground,
    surface      = ChildSurface,
    onPrimary    = Color(0xFF003300),
    onSecondary  = Color.White,
    onBackground = ChildOnBackground,
    onSurface    = ChildOnSurface,
    error        = ChildError
)

@Composable
fun NWChildTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography(),
        content     = content
    )
}