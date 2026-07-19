package io.github.feigepro.checkintrace.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Colors = lightColorScheme(
    primary = Color(0xFF4758D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E7FF),
    onPrimaryContainer = Color(0xFF18215F),
    secondary = Color(0xFF5B5F7A),
    secondaryContainer = Color(0xFFE2E5F6),
    onSecondaryContainer = Color(0xFF1A1D2E),
    background = Color(0xFFF5F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEAEBF1),
    outline = Color(0xFF777985),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

@Composable
fun SignInTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, shapes = AppShapes, content = content)
}
