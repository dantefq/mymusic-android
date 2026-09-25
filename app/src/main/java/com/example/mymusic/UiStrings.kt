package com.example.mymusic

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.util.Locale

private fun key(english: String): String =
    "ui_" + english.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')

fun Context.localized(english: String): String {
    val id = resources.getIdentifier(key(english), "string", packageName)
    return if (id == 0) english else getString(id)
}

@Composable fun l(english: String): String {
    val context = LocalContext.current
    val id = context.resources.getIdentifier(key(english), "string", context.packageName)
    return if (id == 0) english else stringResource(id)
}
