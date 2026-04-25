package su.reya.coop.storage

interface SecretStorage {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun clear(key: String)
    suspend fun has(key: String): Boolean
}