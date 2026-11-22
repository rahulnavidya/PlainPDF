package dev.plainpdf.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PlainScheme = darkColorScheme(
    primary = Color(0xFFC8D7FF),
    onPrimary = Color(0xFF14213D),
    background = Color(0xFF101216),
    onBackground = Color(0xFFE7E9EF),
    surface = Color(0xFF191D24),
    onSurface = Color(0xFFE7E9EF),
    surfaceVariant = Color(0xFF262C36),
    onSurfaceVariant = Color(0xFFB8C0CE),
    secondary = Color(0xFFB8C8E7)
)

@Composable
fun PlainPdfTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PlainScheme, content = content)
}
