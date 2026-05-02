package su.reya.coop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import su.reya.coop.storage.SecretStorage
import kotlin.time.Duration

class NostrViewModel(
    private val nostr: Nostr,
    private val secretStore: SecretStorage
) : ViewModel() {
    private val _hasSecret = MutableStateFlow<Boolean?>(null)
    val hasSecret = _hasSecret.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating = _isCreating.asStateFlow()

    // User metadata store
    private val _metadataStore = mutableMapOf<PublicKey, MutableStateFlow<Metadata?>>()

    fun getMetadata(pubkey: PublicKey): StateFlow<Metadata?> {
        return _metadataStore.getOrPut(pubkey) { MutableStateFlow(null) }.asStateFlow()
    }

    fun updateMetadata(pubkey: PublicKey, metadata: Metadata) {
        _metadataStore.getOrPut(pubkey) { MutableStateFlow(null) }.value = metadata
    }

    fun initAndConnect(dbPath: String) {
        viewModelScope.launch {
            try {
                // Initialize nostr client
                nostr.init(dbPath)

                // Connect to bootstrap relays
                nostr.connect()

                // Get user's signer secret
                val secret = secretStore.get("user_signer")

                // If no secret is found, show onboarding screen
                if (secret == null) {
                    _hasSecret.value = false
                    return@launch
                }
                _hasSecret.value = true

                // Handle different signer types
                if (secret.startsWith("nsec1")) {
                    val keys = Keys.parse(secret)
                    nostr.setKeySigner(keys)
                } else if (secret.startsWith("bunker://")) {
                    val appKeys = getOrInitAppKeys()
                    val bunker = NostrConnectUri.parse(secret)
                    val remote = NostrConnect(
                        uri = bunker,
                        appKeys = appKeys,
                        timeout = Duration.parse("5"),
                        opts = null
                    )
                    nostr.setRemoteSigner(remote)
                } else {
                    throw IllegalArgumentException("Invalid secret format: $secret")
                }
            } catch (e: Exception) {
                println("Failed to connect: ${e.message}")
            }
        }
    }

    fun startNotificationHandler() {
        viewModelScope.launch {
            nostr.handleNotifications { pubkey, metadata ->
                updateMetadata(pubkey, metadata)
            }
        }
    }

    suspend fun getOrInitAppKeys(): Keys {
        val secret = secretStore.get("app_keys")

        // If app keys are already stored, use them
        if (secret != null) {
            return Keys.parse(secret)
        }

        // Generate new app keys and save to the secret storage
        val keys = Keys.generate()
        secretStore.set("app_keys", keys.secretKey().toBech32())

        return keys
    }

    fun createIdentity(name: String, bio: String, picture: String?) {
        viewModelScope.launch {
            try {
                val keys = Keys.generate()
                val secret = keys.secretKey().toBech32()
                // Set loading state
                _isCreating.value = true
                // Create identity
                nostr.createIdentity(keys, name, bio, picture)
                // Save secret to the secret storage
                secretStore.set("user_signer", secret)
            } catch (e: Exception) {
                println("Create identity failed: $e")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Ensure all relays are disconnect
        viewModelScope.launch {
            withContext(NonCancellable) {
                nostr.disconnect()
            }
        }
    }
}