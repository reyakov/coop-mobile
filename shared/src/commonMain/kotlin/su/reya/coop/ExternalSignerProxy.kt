package su.reya.coop

import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.Event
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent

class ExternalSignerProxy(
    private val handler: ExternalSignerHandler,
    private val packageName: String,
    private val currentUser: PublicKey,
) : AsyncNostrSigner {
    override suspend fun getPublicKeyAsync(): PublicKey {
        return currentUser
    }

    override suspend fun signEventAsync(unsignedEvent: UnsignedEvent): Event? {
        val signedJson = handler.signEvent(unsignedEvent, currentUser) ?: return null
        return Event.fromJson(signedJson)
    }

    override suspend fun nip04EncryptAsync(publicKey: PublicKey, content: String): String {
        return handler.nip04Encrypt(content, publicKey)
            ?: throw Exception("NIP-04 encrypt rejected")
    }

    override suspend fun nip04DecryptAsync(publicKey: PublicKey, encryptedContent: String): String {
        return handler.nip04Decrypt(encryptedContent, publicKey)
            ?: throw Exception("NIP-04 decrypt rejected")
    }

    override suspend fun nip44EncryptAsync(publicKey: PublicKey, content: String): String {
        return handler.nip44Encrypt(content, publicKey, currentUser)
            ?: throw Exception("NIP-44 encrypt rejected")
    }

    override suspend fun nip44DecryptAsync(publicKey: PublicKey, payload: String): String {
        return handler.nip44Decrypt(payload, publicKey, currentUser)
            ?: throw Exception("NIP-44 decrypt rejected")
    }
}