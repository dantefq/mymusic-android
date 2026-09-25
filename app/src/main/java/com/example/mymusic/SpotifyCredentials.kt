package com.example.mymusic

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class SpotifyCredentials(private val context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "spotify_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val http = OkHttpClient()
    val clientId: String get() = prefs.getString("client_id", "") ?: ""
    val clientSecret: String get() = prefs.getString("client_secret", "") ?: ""
    val accessToken: String get() = prefs.getString("manual_token", "") ?: ""
    val connected: Boolean get() = prefs.getBoolean("connected", false)

    suspend fun validateAndSave(id: String, secret: String, token: String): Boolean = withContext(Dispatchers.IO) {
        require(token.isNotBlank() || (id.isNotBlank() && secret.isNotBlank())) {
            context.localized("Enter an access token or a client ID and secret")
        }
        val candidate = if (token.isNotBlank()) token.trim() else requestClientToken(id.trim(), secret.trim()).first
        val valid = validate(candidate)
        prefs.edit().putString("client_id", id.trim()).putString("client_secret", secret.trim())
            .putString("manual_token", token.trim()).putBoolean("connected", valid)
            .remove("cached_token").remove("expires").apply()
        valid
    }

    suspend fun bearer(): String? = withContext(Dispatchers.IO) {
        if (!connected) return@withContext null
        accessToken.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val old = prefs.getString("cached_token", null)
        if (old != null && System.currentTimeMillis() < prefs.getLong("expires", 0)) return@withContext old
        if (clientId.isBlank() || clientSecret.isBlank()) return@withContext null
        val (value, seconds) = requestClientToken(clientId, clientSecret)
        prefs.edit().putString("cached_token", value)
            .putLong("expires", System.currentTimeMillis() + seconds * 1000 - 60_000).apply()
        value
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
    fun markDisconnected() {
        prefs.edit().putBoolean("connected", false).apply()
    }

    private fun requestClientToken(id: String, secret: String): Pair<String, Long> {
        val auth = Base64.encodeToString("$id:$secret".toByteArray(), Base64.NO_WRAP)
        val request = Request.Builder().url("https://accounts.spotify.com/api/token")
            .header("Authorization", "Basic $auth")
            .post(FormBody.Builder().add("grant_type", "client_credentials").build()).build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "${context.localized("Spotify credentials rejected")} (HTTP ${response.code})" }
            val json = JSONObject(response.body!!.string())
            return json.getString("access_token") to json.getLong("expires_in")
        }
    }

    private fun validate(token: String): Boolean {
        val request = Request.Builder().url("https://api.spotify.com/v1/search?q=music&type=track&limit=1")
            .header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { response -> return response.isSuccessful }
    }
}
