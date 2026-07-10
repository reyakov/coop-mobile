package su.reya.coop.nostr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.Event
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

class UniversalSigner(initialSigner: AsyncNostrSigner) : AsyncNostrSigner {
    private val mutex = Mutex()

    @Volatile
    private var signer: AsyncNostrSigner = initialSigner

    private val _publicKeyFlow = MutableStateFlow<PublicKey?>(null)
    val publicKeyFlow = _publicKeyFlow.asStateFlow()

    /**
     * Get the current signer.
     */
    fun get(): AsyncNostrSigner = signer

    /**
     * Switch to a new signer.
     */
    suspend fun switch(newSigner: AsyncNostrSigner) {
        val pubkey = withTimeoutOrNull(20.seconds) { newSigner.getPublicKeyAsync() }
            ?: throw IllegalStateException("Failed to get public key from signer")

        mutex.withLock {
            signer = newSigner
            _publicKeyFlow.value = pubkey
        }
    }

    override suspend fun getPublicKeyAsync(): PublicKey? {
        return get().getPublicKeyAsync()
    }

    override suspend fun signEventAsync(unsignedEvent: UnsignedEvent): Event? {
        return get().signEventAsync(unsignedEvent)
    }

    override suspend fun nip04EncryptAsync(publicKey: PublicKey, content: String): String {
        return get().nip04EncryptAsync(publicKey, content)
    }

    override suspend fun nip04DecryptAsync(publicKey: PublicKey, encryptedContent: String): String {
        return get().nip04DecryptAsync(publicKey, encryptedContent)
    }

    override suspend fun nip44EncryptAsync(publicKey: PublicKey, content: String): String {
        return get().nip44EncryptAsync(publicKey, content)
    }

    override suspend fun nip44DecryptAsync(publicKey: PublicKey, payload: String): String {
        return get().nip44DecryptAsync(publicKey, payload)
    }
}
