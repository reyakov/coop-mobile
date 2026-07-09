package su.reya.coop

interface AppStorage {
    // Plain text storage
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)

    // Encrypted storage
    suspend fun getSecret(key: String): String?
    suspend fun setSecret(key: String, value: String)

    suspend fun clear(key: String)
    suspend fun has(key: String): Boolean
}