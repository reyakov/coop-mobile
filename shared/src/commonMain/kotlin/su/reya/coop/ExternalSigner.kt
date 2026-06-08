package su.reya.coop

import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.Event
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent

interface ExternalSignerLauncher {
    suspend fun launch(
        content: String,
        type: String,
        pubkey: String? = null,
        id: String? = null
    ): String?
}

object ExternalSignerLauncherProvider {
    var launcher: ExternalSignerLauncher? = null
}

/**
 * A cross-platform implementation of AsyncNostrSigner that delegates
 * to a platform-specific launcher (NIP-55 on Android).
 */
class ExternalSigner(private val launcher: ExternalSignerLauncher) : AsyncNostrSigner {
    override suspend fun getPublicKeyAsync(): PublicKey? {
        val result = launcher.launch("", "get_public_key")
        return result?.let { PublicKey.parse(it) }
    }

    override suspend fun signEventAsync(unsignedEvent: UnsignedEvent): Event? {
        val result =
            launcher.launch(unsignedEvent.asJson(), "sign_event", id = unsignedEvent.id()?.toHex())
        return result?.let { Event.fromJson(it) }
    }

    override suspend fun nip04EncryptAsync(publicKey: PublicKey, content: String): String {
        return launcher.launch(content, "nip04_encrypt", publicKey.toHex())
            ?: throw Exception("Encryption failed")
    }

    override suspend fun nip04DecryptAsync(publicKey: PublicKey, encryptedContent: String): String {
        return launcher.launch(encryptedContent, "nip04_decrypt", publicKey.toHex())
            ?: throw Exception("Decryption failed")
    }

    override suspend fun nip44EncryptAsync(publicKey: PublicKey, content: String): String {
        return launcher.launch(content, "nip44_encrypt", publicKey.toHex())
            ?: throw Exception("Encryption failed")
    }

    override suspend fun nip44DecryptAsync(publicKey: PublicKey, payload: String): String {
        return launcher.launch(payload, "nip44_decrypt", publicKey.toHex())
            ?: throw Exception("Decryption failed")
    }
}