package com.example.mymusic

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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
    val systemDark = isSystemInDarkTheme()
    val mode = remember(revision) { prefs.getString("mode", "Dark") }
    val defaultBackground = if (mode == "Light" || mode == "System" && !systemDark) "#F4F0E8" else "#11151B"
    val primary = remember(revision) { parseThemeColor(prefs.getString("accent", "#2855FF").orEmpty()) ?: Color(0xFF2855FF) }
    val background = remember(revision, defaultBackground) { parseThemeColor(prefs.getString("background", defaultBackground).orEmpty()) ?: Color(0xFF11151B) }
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
    val systemDark = isSystemInDarkTheme()
    var mode by remember { mutableStateOf(prefs.getString("mode", "Dark").orEmpty()) }
    var accentHex by remember { mutableStateOf(prefs.getString("accent", "#2855FF").orEmpty()) }
    var backgroundHex by remember { mutableStateOf(prefs.getString("background", if (mode == "Light" || mode == "System" && !systemDark) "#F4F0E8" else "#11151B").orEmpty()) }
    var font by remember { mutableStateOf(prefs.getString("font", "Editorial").orEmpty()) }
    Column {
        Text(l("Appearance"), fontSize = 34.sp, color = white, fontFamily = headingFont, modifier = Modifier.padding(start = 8.dp))
        Text(l("Customize the look and feel of MyMusic."), color = muted, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp))
        ConceptDivider()
        Text(l("Theme"), color = white, fontFamily = headingFont, fontSize = 21.sp, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Light" to Icons.Default.LightMode, "Dark" to Icons.Default.DarkMode, "System" to Icons.Default.PhoneAndroid).forEach { (name, icon) ->
                Column(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(12.dp)).background(if (mode == name) accent.copy(alpha = .14f) else panel)
                    .border(if (mode == name) 2.dp else .5.dp, if (mode == name) accent else white.copy(alpha = .04f), RoundedCornerShape(12.dp))
                    .clickable {
                        mode = name; backgroundHex = if (name == "Light" || name == "System" && !systemDark) "#F4F0E8" else "#11151B"
                        prefs.edit().putString("mode", name).remove("background").apply()
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(icon, null, tint = white, modifier = Modifier.size(24.dp)); Spacer(Modifier.height(6.dp)); Text(l(name), color = white, fontSize = 13.sp)
                }
            }
        }
        ColorSetting(l("Accent color"), accentHex, listOf("#2855FF" to "Cobalt", "#FF987F" to "Coral", "#72D6BA" to "Mint")) {
            accentHex = it
            if (parseThemeColor(it) != null) prefs.edit().putString("accent", it).apply()
        }
        ColorSetting(l("Background color"), backgroundHex, listOf("#11151B" to "Graphite", "#46525E" to "Slate", "#090B10" to "Midnight")) {
            backgroundHex = it
            if (parseThemeColor(it) != null) prefs.edit().putString("background", it).apply()
        }
        ConceptDivider(Modifier.padding(top = 6.dp))
        Text(l("Font"), color = white, fontFamily = headingFont, fontSize = 22.sp, modifier = Modifier.padding(vertical = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(panel)
            .border(.5.dp, white.copy(alpha = .09f), RoundedCornerShape(13.dp))) {
            listOf("Editorial", "Modern", "Rounded", "Mono").forEachIndexed { index, name ->
                Row(Modifier.fillMaxWidth().height(40.dp).background(if (font == name) raised.copy(alpha = .5f) else Color.Transparent)
                    .clickable { font = name; prefs.edit().putString("font", name).apply() }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = font == name, onClick = { font = name; prefs.edit().putString("font", name).apply() })
                    Text(l(name), fontFamily = chosenFont(name), color = white, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text("Aa", fontFamily = chosenFont(name), color = white, fontSize = 21.sp, modifier = Modifier.padding(end = 22.dp))
                }
                if (index < 3) ConceptDivider(Modifier.padding(start = 46.dp, end = 14.dp))
            }
        }
        Text(l("Live preview"), fontFamily = headingFont, fontSize = 22.sp, color = white, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(null, Modifier.size(52.dp), 4.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l("Your music, your style"), fontFamily = chosenFont(font), color = white, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("MyMusic", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            FilledIconButton(onClick = {}, colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary)) { Icon(Icons.Default.PlayArrow, null) }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            accentHex = "#2855FF"; backgroundHex = "#11151B"; font = "Editorial"; mode = "Dark"
            prefs.edit().remove("accent").remove("background").remove("font").remove("mode").apply()
        }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = white)) { Text(l("Reset defaults"), fontFamily = headingFont, fontSize = 16.sp) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun ColorSetting(label: String, value: String, choices: List<Pair<String, String>>, onChange: (String) -> Unit) {
    ConceptDivider(Modifier.padding(top = 14.dp))
    Text(label, color = white, fontFamily = headingFont, fontSize = 21.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        choices.forEach { (hex, name) ->
            Column(Modifier.weight(1f).clickable { onChange(hex) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).border(if (hex.equals(value, true)) 2.dp else 1.dp,
                    if (hex.equals(value, true)) accent else white.copy(alpha = .15f), CircleShape).padding(4.dp)
                    .clip(CircleShape).background(parseThemeColor(hex)!!))
                Text(l(name), color = white, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1)
            }
        }
        OutlinedTextField(value, onChange, singleLine = true, isError = parseThemeColor(value) == null,
            textStyle = TextStyle(fontSize = 12.sp, color = white), shape = RoundedCornerShape(10.dp),
            modifier = Modifier.width(104.dp).height(56.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = muted.copy(alpha = .4f)))
    }
}