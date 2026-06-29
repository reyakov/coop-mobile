package su.reya.coop.nostr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent

/**
 * Platform interface for NIP-55 external signer communication.
 * Implemented on Android; no-op/null on other platforms.
 */
interface ExternalSignerHandler {
    fun isAvailable(): Boolean
    fun setPackageName(packageName: String)
    suspend fun getPublicKey(permissions: String? = null): ExternalSignerResult?
    suspend fun signEvent(event: UnsignedEvent, currentUser: PublicKey): String?
    suspend fun nip04Encrypt(plaintext: String, pubkey: PublicKey): String?
    suspend fun nip04Decrypt(ciphertext: String, pubkey: PublicKey): String?
    suspend fun nip44Encrypt(plaintext: String, pubkey: PublicKey, currentUser: PublicKey): String?
    suspend fun nip44Decrypt(ciphertext: String, pubkey: PublicKey, currentUser: PublicKey): String?
}

@Serializable
data class SignerPermission(
    val type: String,
    val kind: Int? = null,
)

object SignerPermissions {
    fun signEvent(kind: Int? = null) = SignerPermission(type = "sign_event", kind = kind)
    fun nip04Encrypt() = SignerPermission(type = "nip04_encrypt")
    fun nip04Decrypt() = SignerPermission(type = "nip04_decrypt")
    fun nip44Encrypt() = SignerPermission(type = "nip44_encrypt")
    fun nip44Decrypt() = SignerPermission(type = "nip44_decrypt")

    fun toJson(permissions: List<SignerPermission>): String {
        return Json.encodeToString(permissions)
    }
}

data class ExternalSignerResult(
    val pubkey: PublicKey,
    val packageName: String,
)