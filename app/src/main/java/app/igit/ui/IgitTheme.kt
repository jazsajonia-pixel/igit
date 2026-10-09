package app.igit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val IgitColors = darkColorScheme(
    primary = Color(0xFF80C7FF),
    onPrimary = Color(0xFF08131C),
    secondary = Color(0xFF7DE2A2),
    onSecondary = Color(0xFF07170D),
    background = Color(0xFF111211),
    onBackground = Color(0xFFF2F3F3),
    surface = Color(0xFF1D1F20),
    onSurface = Color(0xFFF2F3F3),
    surfaceVariant = Color(0xFF292B2D),
    onSurfaceVariant = Color(0xFFADB2B6),
    error = Color(0xFFFF8585),
    outline = Color(0xFF41464A)
)

@Composable
fun IgitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IgitColors,
        typography = MaterialTheme.typography.copy(
            headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
            labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 0.15.sp)
        ),
        content = content
    )
}
