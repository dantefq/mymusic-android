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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import android.graphics.Typeface
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
internal val headingFont: FontFamily? @Composable get() = MaterialTheme.typography.headlineLarge.fontFamily

private fun chosenFont(name: String): FontFamily = when (name) {
    "Editorial" -> FontFamily.Serif
    "Rounded" -> FontFamily(Typeface.create("casual", Typeface.NORMAL))
    "Mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

private fun appTypography(name: String): Typography {
    val body = if (name == "Editorial") FontFamily.SansSerif else chosenFont(name)
    val heading = chosenFont(name)
    val base = Typography()
    fun TextStyle.body() = copy(fontFamily = body)
    fun TextStyle.heading() = copy(fontFamily = heading)
    return Typography(
        displayLarge = base.displayLarge.heading(), displayMedium = base.displayMedium.heading(), displaySmall = base.displaySmall.heading(),
        headlineLarge = base.headlineLarge.heading(), headlineMedium = base.headlineMedium.heading(), headlineSmall = base.headlineSmall.heading(),
        titleLarge = base.titleLarge.heading(), titleMedium = base.titleMedium.body(), titleSmall = base.titleSmall.body(),
        bodyLarge = base.bodyLarge.body(), bodyMedium = base.bodyMedium.body(), bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body(), labelMedium = base.labelMedium.body(), labelSmall = base.labelSmall.body())
}

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
    val typography = remember(revision) { appTypography(prefs.getString("font", "Editorial").orEmpty()) }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = primary, onPrimary = if (primary.luminance() > .4f) Color.Black else Color.White,
        background = background, onBackground = foreground,
        surface = lerp(background, foreground, .055f), onSurface = foreground,
        surfaceVariant = lerp(background, foreground, .12f), onSurfaceVariant = lerp(background, foreground, .72f),
        outline = Color(0xFF8D99AA), error = Color(0xFFFF9B9B)
    ), typography = typography, content = content)
}

private fun parseThemeColor(value: String): Color? =
    if (value.matches(Regex("#[0-9a-fA-F]{6}"))) Color(android.graphics.Color.parseColor(value)) else null

@Composable fun AppearanceSettings() {
    val prefs = LocalContext.current.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var accentHex by remember { mutableStateOf(prefs.getString("accent", "#527BFF").orEmpty()) }
    var backgroundHex by remember { mutableStateOf(prefs.getString("background", "#11151B").orEmpty()) }
    var font by remember { mutableStateOf(prefs.getString("font", "Editorial").orEmpty()) }
    Text(l("Appearance"), fontSize = 25.sp, color = white, fontFamily = headingFont)
    Text(l("Customize colors and type"), color = muted)
    Spacer(Modifier.height(12.dp))
    ColorSetting(l("Accent color"), accentHex, listOf("#527BFF", "#FF987F", "#72D6BA")) { accentHex = it }
    ColorSetting(l("Background color"), backgroundHex, listOf("#11151B", "#26303B", "#090B10")) { backgroundHex = it }
    Button(onClick = {
        prefs.edit().putString("accent", accentHex).putString("background", backgroundHex).apply()
    }, enabled = parseThemeColor(accentHex) != null && parseThemeColor(backgroundHex) != null,
        modifier = Modifier.fillMaxWidth()) { Text(l("Apply colors")) }
    Text(l("Font"), color = white, fontFamily = headingFont, fontSize = 22.sp)
    listOf("Editorial", "Modern", "Rounded", "Mono").forEach { name ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected = font == name, onClick = {
                font = name; prefs.edit().putString("font", name).apply()
            })
            TextButton(onClick = { font = name; prefs.edit().putString("font", name).apply() }) {
                Text(l(name), fontFamily = chosenFont(name), color = white)
            }
        }
    }
    Text(l("Live preview"), color = muted)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("MyMusic", style = MaterialTheme.typography.headlineMedium)
            Text(l("Your music, your style"))
        }
    }
    TextButton(onClick = {
        accentHex = "#527BFF"; backgroundHex = "#11151B"; font = "Editorial"
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
