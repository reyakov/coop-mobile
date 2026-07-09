package su.reya.coop

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore("secret_store")

data class SecretEntry(val encrypted: String, val iv: String)

class SecretCrypto {
    private val keyAlias = "coop"
    private val keyStoreType = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"

    fun encrypt(content: String): SecretEntry {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val encrypted = cipher.doFinal(content.toByteArray())
        val iv = cipher.iv

        return SecretEntry(
            encrypted = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decrypt(entry: SecretEntry): String {
        val encrypted = Base64.decode(entry.encrypted, Base64.NO_WRAP)
        val iv = Base64.decode(entry.iv, Base64.NO_WRAP)

        val cipher = Cipher.getInstance(transformation)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

        val plaintext = cipher.doFinal(encrypted)

        return String(plaintext, StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(keyStoreType).apply { load(null) }
        val existingKey = keyStore.getKey(keyAlias, null)

        if (existingKey is SecretKey) return existingKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreType)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}

class AppStore(private val context: Context) : AppStorage {
    private val crypto = SecretCrypto()

    override suspend fun get(key: String): String? {
        return context.dataStore.data.first()[stringPreferencesKey(key)]
    }

    override suspend fun set(key: String, value: String) {
        context.dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun getSecret(key: String): String? {
        val prefs = context.dataStore.data.first()
        val encrypted = prefs[stringPreferencesKey("${key}_encrypted")] ?: return null
        val iv = prefs[stringPreferencesKey("${key}_iv")] ?: return null

        return crypto.decrypt(SecretEntry(encrypted, iv))
    }

    override suspend fun setSecret(key: String, value: String) {
        val entry = crypto.encrypt(value)
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("${key}_encrypted")] = entry.encrypted
            prefs[stringPreferencesKey("${key}_iv")] = entry.iv
        }
    }

    override suspend fun clear(key: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
            prefs.remove(stringPreferencesKey("${key}_encrypted"))
            prefs.remove(stringPreferencesKey("${key}_iv"))
        }
    }

    override suspend fun has(key: String): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs.contains(stringPreferencesKey(key)) ||
                prefs.contains(stringPreferencesKey("${key}_encrypted"))
    }
}
