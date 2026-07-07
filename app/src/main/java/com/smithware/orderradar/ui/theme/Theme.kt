package com.smithware.orderradar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RadarLime = Color(0xFF45C08A)
val RadarOrange = Color(0xFFD99A3D)
val RadarRed = Color(0xFFE35D5B)
val RadarCharcoal = Color(0xFF101312)
val RadarPanel = Color(0xFF191D1B)
val RadarCard = Color(0xFF232824)
val RadarText = Color(0xFFF2F3EE)
val RadarMuted = Color(0xFFABB4AD)

private val Scheme = darkColorScheme(
    primary = RadarLime,
    secondary = RadarOrange,
    tertiary = Color(0xFFC9CED1),
    background = RadarCharcoal,
    surface = RadarPanel,
    surfaceVariant = RadarCard,
    onPrimary = Color(0xFF071611),
    onSecondary = Color(0xFF201306),
    onBackground = RadarText,
    onSurface = RadarText,
    error = RadarRed
)

@Composable
fun OrderRadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
