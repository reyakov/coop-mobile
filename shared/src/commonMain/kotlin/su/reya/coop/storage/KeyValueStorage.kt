package su.reya.coop.storage

import kotlinx.coroutines.flow.Flow

interface KeyValueStorage {
    suspend fun get(key: String): String?
    fun getFlow(key: String): Flow<String?>
    suspend fun set(key: String, value: String)
    suspend fun clear(key: String)
    suspend fun has(key: String): Boolean
}
