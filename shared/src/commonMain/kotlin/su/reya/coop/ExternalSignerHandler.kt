package su.reya.coop

import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent

/**
 * Platform interface for NIP-55 external signer communication.
 * Implemented on Android; no-op/null on other platforms.
 */
interface ExternalSignerHandler {
    fun isAvailable(): Boolean

    suspend fun getPublicKey(permissions: String? = null): ExternalSignerResult?
    suspend fun signEvent(event: UnsignedEvent, currentUser: PublicKey): String?
    suspend fun nip04Encrypt(plaintext: String, pubkey: PublicKey): String?
    suspend fun nip04Decrypt(ciphertext: String, pubkey: PublicKey): String?
    suspend fun nip44Encrypt(plaintext: String, pubkey: PublicKey, currentUser: PublicKey): String?
    suspend fun nip44Decrypt(ciphertext: String, pubkey: PublicKey, currentUser: PublicKey): String?
}

data class ExternalSignerResult(
    val pubkey: String,
    val packageName: String,
)