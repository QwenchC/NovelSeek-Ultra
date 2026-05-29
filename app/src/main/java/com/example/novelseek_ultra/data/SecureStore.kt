package com.example.novelseek_ultra.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wraps EncryptedSharedPreferences (Android Keystore-backed AES-256-GCM) for everything that the
 * PC build stored as plain text in `localStorage`:
 *   - per-profile text model `apiKey`s
 *   - active text model `apiKey`
 *   - Pollinations key
 *   - Embedding `apiKey`
 *
 * Keys here use the form "textModelProfile:<profileId>", "textModelConfig", "embeddingConfig",
 * "pollinationsKey".
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(key: String): String = prefs.getString(key, "").orEmpty()

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun putAll(entries: Map<String, String>) {
        prefs.edit().apply {
            for ((k, v) in entries) putString(k, v)
        }.apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "novelseek_secure_v1"

        fun profileKey(profileId: String) = "textModelProfile:$profileId"
        const val TEXT_MODEL_CONFIG_KEY = "textModelConfig"
        const val EMBEDDING_CONFIG_KEY = "embeddingConfig"
        const val POLLINATIONS_KEY = "pollinationsKey"
    }
}