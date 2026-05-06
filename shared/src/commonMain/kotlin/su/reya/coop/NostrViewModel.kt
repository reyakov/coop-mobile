package su.reya.coop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import su.reya.coop.storage.SecretStorage
import kotlin.time.Clock
import kotlin.time.Duration

class NostrViewModel(
    private val nostr: Nostr,
    private val secretStore: SecretStorage
) : ViewModel() {
    private val _hasSecret = MutableStateFlow<Boolean?>(null)
    val hasSecret = _hasSecret.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating = _isCreating.asStateFlow()

    private val _chatRooms = MutableStateFlow<Set<Room>>(emptySet())
    val chatRooms = _chatRooms.asStateFlow()

    private val _metadataStore = mutableMapOf<PublicKey, MutableStateFlow<Metadata?>>()
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)
    private val seenPublicKeys = mutableSetOf<PublicKey>()

    init {
        startMetadataBatchProcessor()
    }

    private fun startMetadataBatchProcessor() {
        viewModelScope.launch {
            val batch = mutableSetOf<PublicKey>()
            val timeout = 500L // 500ms timeout for batching

            while (true) {
                val firstKey = metadataRequestChannel.receive()
                batch.add(firstKey)
                val lastFlushTime = Clock.System.now().toEpochMilliseconds()

                while (batch.isNotEmpty()) {
                    val nextKey = withTimeoutOrNull(timeout) {
                        metadataRequestChannel.receive()
                    }

                    if (nextKey != null) {
                        batch.add(nextKey)
                    }

                    val now = Clock.System.now().toEpochMilliseconds()
                    if (batch.size >= 20 || (now - lastFlushTime) >= timeout || nextKey == null) {
                        val keysToRequest = batch.toList()
                        batch.clear()
                        nostr.fetchMetadataBatch(keysToRequest)
                    }
                }
            }
        }
    }

    fun requestMetadata(pubkey: PublicKey) {
        if (seenPublicKeys.add(pubkey)) {
            viewModelScope.launch {
                metadataRequestChannel.send(pubkey)
            }
        }
    }

    fun getMetadata(pubkey: PublicKey): StateFlow<Metadata?> {
        val flow = _metadataStore.getOrPut(pubkey) { MutableStateFlow(null) }
        if (flow.value == null) {
            requestMetadata(pubkey)
        }
        return flow.asStateFlow()
    }

    fun updateMetadata(pubkey: PublicKey, metadata: Metadata) {
        _metadataStore.getOrPut(pubkey) { MutableStateFlow(null) }.value = metadata
    }

    fun getUserProfile(): StateFlow<Metadata?> {
        return try {
            getMetadata(nostr.userPubkey!!)
        } catch (e: Exception) {
            MutableStateFlow(null)
        }
    }

    fun initAndConnect(dbPath: String) {
        viewModelScope.launch {
            try {
                // Initialize nostr client
                nostr.init(dbPath)
                // Connect to bootstrap relays
                nostr.connect()
                // Get user's secret
                getUserSecret()
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

    suspend fun getUserSecret() {
        // Get user's signer secret
        val secret = secretStore.get("user_signer")

        // If no secret is found, show onboarding screen
        if (secret == null) {
            _hasSecret.value = false
            return
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

    fun importIdentity(secret: String) {
        // TODO: Implement import
    }


    fun getChatRooms() {
        viewModelScope.launch {
            try {
                _chatRooms.value = nostr.getChatRooms() ?: emptySet()
            } catch (e: Exception) {
                println("Failed to get chat rooms: ${e.message}")
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