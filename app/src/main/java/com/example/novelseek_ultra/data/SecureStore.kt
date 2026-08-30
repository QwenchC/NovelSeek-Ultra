package com.example.novelseek_ultra.data

import android.annotation.SuppressLint
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
internal class SecureStore(context: Context) : BackupImportSecretRollbackStore {

    private data class RollbackValue(val present: Boolean, val value: String?)

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

    @SuppressLint("UseKtx") // Import commit must confirm that every live secret is durable.
    fun putAllDurably(entries: Map<String, String>) {
        validateBackupImportSecretKeys(entries.keys.toList())
        val editor = prefs.edit().apply {
            for ((k, v) in entries) putString(k, v)
        }
        check(editor.commit()) { "无法持久化导入的加密密钥" }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    @SuppressLint("UseKtx") // Crash recovery needs commit()'s success result before proceeding.
    override fun stageRollbackValues(secretKeys: List<String>) {
        validateBackupImportSecretKeys(secretKeys)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(IMPORT_ROLLBACK_PREFIX) }.forEach(editor::remove)
        editor.putInt(IMPORT_ROLLBACK_COUNT_KEY, secretKeys.size)
        secretKeys.forEachIndexed { index, key ->
            val present = prefs.contains(key)
            editor.putString(rollbackKeySlot(index), key)
            editor.putBoolean(rollbackPresenceSlot(index), present)
            if (present) editor.putString(rollbackValueSlot(index), prefs.getString(key, "").orEmpty())
        }
        check(editor.commit()) { "无法持久化导入密钥回滚快照" }
    }

    override fun validateRollbackValues(secretKeys: List<String>) {
        readRollbackValues(secretKeys)
    }

    private fun readRollbackValues(secretKeys: List<String>): List<RollbackValue> {
        validateBackupImportSecretKeys(secretKeys)
        check(prefs.getInt(IMPORT_ROLLBACK_COUNT_KEY, -1) == secretKeys.size) {
            "导入密钥回滚快照数量不匹配"
        }
        return secretKeys.mapIndexed { index, key ->
            val keySlot = rollbackKeySlot(index)
            val presenceSlot = rollbackPresenceSlot(index)
            check(prefs.contains(keySlot) && prefs.getString(keySlot, null) == key) {
                "导入密钥回滚快照标识不匹配"
            }
            check(prefs.contains(presenceSlot)) { "导入密钥回滚快照缺少存在性标记" }
            val present = prefs.getBoolean(presenceSlot, false)
            val value = if (present) {
                val valueSlot = rollbackValueSlot(index)
                check(prefs.contains(valueSlot)) { "导入密钥回滚快照缺少加密值" }
                prefs.getString(valueSlot, "").orEmpty()
            } else {
                null
            }
            RollbackValue(present, value)
        }
    }

    @SuppressLint("UseKtx") // Recovery must not publish restored state before secrets are durable.
    override fun restoreRollbackValues(secretKeys: List<String>) {
        val snapshots = readRollbackValues(secretKeys)
        val editor = prefs.edit()
        secretKeys.forEachIndexed { index, key ->
            val (present, value) = snapshots[index]
            if (present) editor.putString(key, value) else editor.remove(key)
        }
        check(editor.commit()) { "无法恢复导入前的加密密钥" }
    }

    @SuppressLint("UseKtx") // Marker cleanup requires a confirmed synchronous preference write.
    override fun clearRollbackValues() {
        val keys = prefs.all.keys.filter { it.startsWith(IMPORT_ROLLBACK_PREFIX) }
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        keys.forEach(editor::remove)
        check(editor.commit()) { "无法清理导入密钥回滚快照" }
    }

    companion object {
        private const val FILE_NAME = "novelseek_secure_v1"
        private const val IMPORT_ROLLBACK_PREFIX = "__backupImportRollback:"
        private const val IMPORT_ROLLBACK_COUNT_KEY = "${IMPORT_ROLLBACK_PREFIX}count"

        private fun rollbackKeySlot(index: Int) = "${IMPORT_ROLLBACK_PREFIX}$index:key"
        private fun rollbackPresenceSlot(index: Int) = "${IMPORT_ROLLBACK_PREFIX}$index:present"
        private fun rollbackValueSlot(index: Int) = "${IMPORT_ROLLBACK_PREFIX}$index:value"

        const val TEXT_MODEL_PROFILE_KEY_PREFIX = "textModelProfile:"
        fun profileKey(profileId: String) = "$TEXT_MODEL_PROFILE_KEY_PREFIX$profileId"
        const val TEXT_MODEL_CONFIG_KEY = "textModelConfig"
        const val EMBEDDING_CONFIG_KEY = "embeddingConfig"
        const val POLLINATIONS_KEY = "pollinationsKey"
    }
}
