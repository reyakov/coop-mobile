package su.reya.coop.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("secret_store")

class SecretStore(private val context: Context) : SecretStorage {
    private val crypto = SecretCrypto()

    override suspend fun set(key: String, value: String) {
        val entry = crypto.encrypt(value)

        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("${key}_encrypted")] = entry.encrypted
            prefs[stringPreferencesKey("${key}_iv")] = entry.iv
        }
    }

    override suspend fun get(key: String): String? {
        val prefs = context.dataStore.data.first()
        val encrypted = prefs[stringPreferencesKey("${key}_encrypted")] ?: return null
        val iv = prefs[stringPreferencesKey("${key}_iv")] ?: return null

        return crypto.decrypt(SecretEntry(encrypted, iv))
    }

    override suspend fun clear(key: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("${key}_encrypted"))
            prefs.remove(stringPreferencesKey("${key}_iv"))
        }
    }

    override suspend fun has(key: String): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[stringPreferencesKey("${key}_encrypted")] != null
    }
}