package su.reya.coop.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingStorage(
    private val dataStore: DataStore<Preferences>
) : KeyValueStorage {
    override suspend fun get(key: String): String? {
        return dataStore.data.map { it[stringPreferencesKey(key)] }.first()
    }

    override fun getFlow(key: String): Flow<String?> {
        return dataStore.data.map { it[stringPreferencesKey(key)] }
    }

    override suspend fun set(key: String, value: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun clear(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    override suspend fun has(key: String): Boolean {
        return dataStore.data.map { it.contains(stringPreferencesKey(key)) }.first()
    }
}
