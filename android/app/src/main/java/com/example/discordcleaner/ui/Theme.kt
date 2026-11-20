package com.example.discordcleaner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val bg = Color(0xFF1E1F24)
    val surface = Color(0xFF2A2C33)
    val raised = Color(0xFF34363E)
    val fg = Color(0xFFECEEF3)
    val muted = Color(0xFF9AA0AD)
    val accent = Color(0xFF6C7BFF)
    val mint = Color(0xFF5EE6B0)
    val danger = Color(0xFFE2564F)
    val warn = Color(0xFFF0B04A)
    val avatars = listOf(Color(0xFFFFB86B), Color(0xFF7AD3FF), Color(0xFFC3A6FF), Color(0xFF8BE38B), Color(0xFFFF8FA8), Color(0xFFFFE27A))
}

@Composable
fun CleanerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = Color.White,
            secondary = C.mint, onSecondary = Color(0xFF0E2A20),
            background = C.bg, onBackground = C.fg,
            surface = C.bg, onSurface = C.fg,
            surfaceVariant = C.surface, onSurfaceVariant = C.muted,
            surfaceContainer = C.surface, surfaceContainerHigh = C.raised, surfaceContainerLow = C.surface,
            surfaceContainerHighest = C.raised,
            secondaryContainer = C.raised, onSecondaryContainer = C.fg,
            error = C.danger, outline = Color(0xFF4A4D57), outlineVariant = Color(0xFF3B3E47),
        ),
        content = content,
    )
}
