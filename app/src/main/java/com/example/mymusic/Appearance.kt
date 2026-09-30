package com.example.mymusic

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val prefs = LocalContext.current.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val primary = remember(revision) { parseThemeColor(prefs.getString("accent", "#527BFF").orEmpty()) ?: Color(0xFF527BFF) }
    val background = remember(revision) { parseThemeColor(prefs.getString("background", "#11151B").orEmpty()) ?: Color(0xFF11151B) }
    val foreground = if (background.luminance() > .45f) Color(0xFF151A22) else Color(0xFFF4F0E8)
    MaterialTheme(colorScheme = darkColorScheme(
        primary = primary, onPrimary = if (primary.luminance() > .4f) Color.Black else Color.White,
        background = background, onBackground = foreground,
        surface = lerp(background, foreground, .055f), onSurface = foreground,
        surfaceVariant = lerp(background, foreground, .12f), onSurfaceVariant = lerp(background, foreground, .72f),
        outline = Color(0xFF8D99AA), error = Color(0xFFFF9B9B)
    ), content = content)
}

private fun parseThemeColor(value: String): Color? =
    if (value.matches(Regex("#[0-9a-fA-F]{6}"))) Color(android.graphics.Color.parseColor(value)) else null

@Composable fun AppearanceSettings() {
    val prefs = LocalContext.current.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var accentHex by remember { mutableStateOf(prefs.getString("accent", "#527BFF").orEmpty()) }
    var backgroundHex by remember { mutableStateOf(prefs.getString("background", "#11151B").orEmpty()) }
    Text(l("Appearance"), fontSize = 25.sp, color = white)
    Text(l("Customize colors and type"), color = muted)
    Spacer(Modifier.height(12.dp))
    ColorSetting(l("Accent color"), accentHex, listOf("#527BFF", "#FF987F", "#72D6BA")) { accentHex = it }
    ColorSetting(l("Background color"), backgroundHex, listOf("#11151B", "#26303B", "#090B10")) { backgroundHex = it }
    Button(onClick = {
        prefs.edit().putString("accent", accentHex).putString("background", backgroundHex).apply()
    }, enabled = parseThemeColor(accentHex) != null && parseThemeColor(backgroundHex) != null,
        modifier = Modifier.fillMaxWidth()) { Text(l("Apply colors")) }
    TextButton(onClick = {
        accentHex = "#527BFF"; backgroundHex = "#11151B"
        prefs.edit().remove("accent").remove("background").remove("font").apply()
    }) { Text(l("Reset defaults")) }
    HorizontalDivider(color = raised)
}

@Composable private fun ColorSetting(label: String, value: String, choices: List<String>, onChange: (String) -> Unit) {
    Text(label, color = white)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { hex ->
            FilledTonalButton(onClick = { onChange(hex) }, shape = CircleShape,
                contentPadding = PaddingValues(8.dp), modifier = Modifier.size(48.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = parseThemeColor(hex)!!)) {
                Text(if (hex.equals(value, true)) "✓" else "", color = Color.White)
            }
        }
    }
    OutlinedTextField(value, onChange, label = { Text(label + " · #RRGGBB") }, singleLine = true,
        isError = parseThemeColor(value) == null, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
}
