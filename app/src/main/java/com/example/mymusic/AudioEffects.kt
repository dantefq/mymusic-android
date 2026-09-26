package com.example.mymusic

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

object AudioEffects {
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var loudnessEnhancer: LoudnessEnhancer? = null
    fun setEnhanced(context: Context, enabled: Boolean) {
        context.getSharedPreferences("eq", Context.MODE_PRIVATE).edit()
            .putBoolean("enhancer", enabled).apply()
        runCatching { loudnessEnhancer?.enabled = enabled }
    }
    fun bandCount(): Int = equalizer?.numberOfBands?.toInt() ?: 0
    fun range(): IntRange {
        val r = equalizer?.bandLevelRange ?: return -1500..1500
        return r[0].toInt()..r[1].toInt()
    }
    fun level(band: Int): Int = equalizer?.getBandLevel(band.toShort())?.toInt() ?: 0
    fun frequency(band: Int): Int = equalizer?.getCenterFreq(band.toShort())?.div(1000) ?: 0
    fun setBand(context: Context, band: Int, level: Int) {
        equalizer?.setBandLevel(band.toShort(), level.coerceIn(range()).toShort())
        save(context)
    }
    fun setBass(context: Context, strength: Int) {
        bassBoost?.setStrength(strength.coerceIn(0, 1000).toShort())
        save(context)
    }
    fun bass(): Int = bassBoost?.roundedStrength?.toInt() ?: 0
    fun builtInPresets(): List<String> = equalizer?.let { eq ->
        (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
    } ?: emptyList()
    fun usePreset(context: Context, index: Int) {
        equalizer?.usePreset(index.toShort()); save(context)
    }
    fun restore(context: Context) {
        val prefs = context.getSharedPreferences("eq", Context.MODE_PRIVATE)
        prefs.getString("levels", null)?.split(",")?.forEachIndexed { i, value ->
            if (i < bandCount()) runCatching { equalizer?.setBandLevel(i.toShort(), value.toInt().toShort()) }
        }
        bassBoost?.setStrength(prefs.getInt("bass", 0).toShort())
        runCatching { loudnessEnhancer?.enabled = prefs.getBoolean("enhancer", false) }
    }
    private fun save(context: Context) {
        context.getSharedPreferences("eq", Context.MODE_PRIVATE).edit()
            .putString("levels", (0 until bandCount()).joinToString(",") { level(it).toString() })
            .putInt("bass", bass()).apply()
    }
}
