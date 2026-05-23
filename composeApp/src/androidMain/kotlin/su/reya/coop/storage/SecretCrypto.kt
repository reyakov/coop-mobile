package su.reya.coop.coop.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SecretEntry(
    val encrypted: String,
    val iv: String
)

class SecretCrypto {
    private val keyAlias = "coop"
    private val keyStoreType = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"

    fun encrypt(content: String): SecretEntry {
        // Initialize cipher
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        // Encrypt content
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

        // Initialize cipher
        val cipher = Cipher.getInstance(transformation)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

        // Decrypt content
        val plaintext = cipher.doFinal(encrypted)

        return String(plaintext, StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(keyStoreType).apply { load(null) }
        val existingKey = keyStore.getKey(keyAlias, null)

        // Return existing key if available
        if (existingKey is SecretKey) return existingKey

        // Construct a new key generator
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreType)

        // Initialize key generation parameters
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        // Generate a new key
        keyGenerator.init(spec)

        return keyGenerator.generateKey()
    }
}