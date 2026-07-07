package com.smithware.orderradar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RadarLime = Color(0xFFB6F238)
val RadarOrange = Color(0xFFF59E0B)
val RadarRed = Color(0xFFEF4444)
val RadarCharcoal = Color(0xFF111311)
val RadarPanel = Color(0xFF1B1D1A)
val RadarCard = Color(0xFF242720)
val RadarText = Color(0xFFF7F5EA)
val RadarMuted = Color(0xFFAEB4A5)

private val Scheme = darkColorScheme(
    primary = RadarLime,
    secondary = RadarOrange,
    tertiary = Color(0xFFC9CED1),
    background = RadarCharcoal,
    surface = RadarPanel,
    surfaceVariant = RadarCard,
    onPrimary = Color(0xFF182000),
    onSecondary = Color(0xFF261500),
    onBackground = RadarText,
    onSurface = RadarText,
    error = RadarRed
)

@Composable
fun OrderRadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
