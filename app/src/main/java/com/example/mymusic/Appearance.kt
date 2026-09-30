package com.example.mymusic

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val ink: Color @Composable get() = MaterialTheme.colorScheme.background
internal val panel: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val raised: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val white: Color @Composable get() = MaterialTheme.colorScheme.onSurface

@Composable fun MyMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF527BFF), onPrimary = Color.White,
        background = Color(0xFF11151B), onBackground = Color(0xFFF4F0E8),
        surface = Color(0xFF1B222B), onSurface = Color(0xFFF4F0E8),
        surfaceVariant = Color(0xFF2A333F), onSurfaceVariant = Color(0xFFB8C0CD),
        outline = Color(0xFF8D99AA), error = Color(0xFFFF9B9B)
    ), content = content)
}
