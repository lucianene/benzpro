package app.benzpro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ConnectGreen = Color(0xFF2E7D32)
val DisconnectRed = Color(0xFFC62828)
val LogBg = Color(0xFF010409)
val TxCyan = Color(0xFF56D4F5)
val RxGreen = Color(0xFF7EE787)
val InfoGray = Color(0xFF8B949E)
val WarnAmber = Color(0xFFE3B341)
val ErrorRed = Color(0xFFFF7B72)
val SuccessGreen = Color(0xFF3FB950)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3D8BFF),
    onPrimary = Color.White,
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF21262D),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFFF7B72),
)

@Composable
fun BenzProTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
