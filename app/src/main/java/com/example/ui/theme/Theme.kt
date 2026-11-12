package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val InterceptorDarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = Color(0xFF001F24),
    primaryContainer = Color(0xFF00363D),
    onPrimaryContainer = CyberCyan,
    secondary = EmeraldAllow,
    onSecondary = Color(0xFF002114),
    secondaryContainer = EmeraldAllowContainer,
    onSecondaryContainer = Color(0xFF6EE7B7),
    tertiary = AmberPending,
    onTertiary = Color(0xFF2A1200),
    tertiaryContainer = AmberPendingContainer,
    onTertiaryContainer = Color(0xFFFDE68A),
    error = CrimsonBlock,
    onError = Color.White,
    errorContainer = CrimsonBlockContainer,
    onErrorContainer = Color(0xFFFCA5A5),
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = ObsidianBorder,
    outlineVariant = Color(0xFF1E293B)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = InterceptorDarkColorScheme,
        typography = Typography,
        content = content
    )
}
