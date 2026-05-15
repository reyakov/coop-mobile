package su.reya.coop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.blossom.BlossomClient
import su.reya.coop.storage.SecretStorage
import kotlin.time.Clock
import kotlin.time.Duration

class NostrViewModel(
    private val nostr: Nostr,
    private val secretStore: SecretStorage
) : ViewModel() {
    private val _emptySecret = MutableStateFlow<Boolean?>(null)
    val emptySecret = _emptySecret.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating = _isCreating.asStateFlow()

    private val _chatRooms = MutableStateFlow<Set<Room>>(emptySet())
    val chatRooms = _chatRooms.asStateFlow()

    private val _newEvents = MutableSharedFlow<UnsignedEvent>(extraBufferCapacity = 100)
    val newEvents = _newEvents.asSharedFlow()

    private val _errorEvents = Channel<String>(Channel.BUFFERED)
    val errorEvents = _errorEvents.receiveAsFlow()

    private val _metadataStore = mutableMapOf<PublicKey, MutableStateFlow<Metadata?>>()
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)
    private val seenPublicKeys = mutableSetOf<PublicKey>()

    init {
        startMetadataBatchProcessor()
    }

    private fun showError(message: String) {
        viewModelScope.launch {
            _errorEvents.send(message)

            if (isCreating.value) {
                _isCreating.value = false
            }
        }
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
                    if (batch.size >= 10 || (now - lastFlushTime) >= timeout || nextKey == null) {
                        val keysToRequest = batch.toList()
                        batch.clear()
                        nostr.fetchMetadataBatch(keysToRequest)
                    }
                }
            }
        }
    }

    private fun requestMetadata(pubkey: PublicKey) {
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

    private fun updateMetadata(pubkey: PublicKey, metadata: Metadata) {
        _metadataStore.getOrPut(pubkey) { MutableStateFlow(null) }.value = metadata
    }

    suspend fun initAndConnect(dbPath: String) {
        try {
            // Initialize nostr client
            nostr.init(dbPath)
            // Get user's secret
            getUserSecret()
        } catch (e: Exception) {
            showError("Failed to initialize Nostr: ${e.message}")
        }
    }

    fun startNotificationHandler() {
        viewModelScope.launch {
            nostr.handleNotifications(
                onMetadataUpdate = { pubkey, metadata ->
                    updateMetadata(pubkey, metadata)
                },
                onEose = {
                    getChatRooms()
                },
                onNewMessage = { event ->
                    viewModelScope.launch {
                        _newEvents.emit(event)
                    }
                },
            )
        }
    }

    fun currentUser(): PublicKey? {
        return nostr.signer.currentUser
    }

    fun logout() {
        viewModelScope.launch {
            _emptySecret.value = true
            _chatRooms.value = emptySet()
            secretStore.clear("user_signer")
            nostr.exit()
        }
    }

    suspend fun getUserSecret() {
        // Get user's signer secret
        val secret = secretStore.get("user_signer")

        // If no secret is found, show onboarding screen
        when (secret) {
            null -> {
                _emptySecret.value = true
                return
            }

            else -> _emptySecret.value = false
        }

        // Handle different signer types
        if (secret.startsWith("nsec1")) {
            val keys = Keys.parse(secret)
            nostr.setSigner(keys)
        } else if (secret.startsWith("bunker://")) {
            try {
                val appKeys = getOrInitAppKeys()
                val bunker = NostrConnectUri.parse(secret)
                val timeout = Duration.parse("50s") // 50 seconds timeout
                val remote = NostrConnect(uri = bunker, appKeys = appKeys, timeout = timeout, null)
                nostr.setSigner(remote)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
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

    fun createIdentity(
        name: String,
        bio: String,
        picture: ByteArray?,
        contentType: String?
    ) {
        viewModelScope.launch {
            try {
                val keys = Keys.generate()
                val secret = keys.secretKey().toBech32()
                var avatarUrl = ""

                // Set loading state
                _isCreating.value = true

                // Upload picture to Blossom
                if (picture != null) {
                    val blossom = BlossomClient(
                        url = "https://blossom.band",
                        client = HttpClient {
                            install(ContentNegotiation) {
                                json(Json {
                                    ignoreUnknownKeys = true
                                    prettyPrint = true
                                    isLenient = true
                                })
                            }
                        }
                    )

                    val descriptor = blossom.upload(
                        file = picture,
                        contentType = contentType,
                        signer = keys
                    )

                    avatarUrl = descriptor?.url ?: ""
                }

                // Create identity
                nostr.createIdentity(keys = keys, name = name, bio = bio, picture = avatarUrl)

                // Save secret to the secret storage
                secretStore.set("user_signer", secret)

                // Set an empty secret state
                _emptySecret.value = false
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun importIdentity(secret: String) {
        viewModelScope.launch {
            if (secret.startsWith("nsec1")) {
                val keys = Keys.parse(secret)
                nostr.setSigner(keys)
                secretStore.set("user_signer", secret)
                // Set an empty secret state
                _emptySecret.value = false
            } else if (secret.startsWith("bunker://")) {
                try {
                    val appKeys = getOrInitAppKeys()
                    val bunker = NostrConnectUri.parse(secret)
                    val timeout = Duration.parse("50s") // 50 seconds timeout
                    val remote =
                        NostrConnect(uri = bunker, appKeys = appKeys, timeout = timeout, null)
                    nostr.setSigner(remote)
                    secretStore.set("user_signer", secret)
                    // Set an empty secret state
                    _emptySecret.value = false
                } catch (e: Exception) {
                    showError("Error: ${e.message}")
                }
            } else {
                showError("Please enter a valid Secret or Bunker URI.")
            }
        }
    }

    fun getChatRoom(id: Long): Room {
        return chatRooms.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Room not found")
    }

    fun getChatRooms() {
        viewModelScope.launch {
            try {
                _chatRooms.value = nostr.getChatRooms() ?: emptySet()
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    suspend fun getChatRoomMessages(roomId: Long): List<UnsignedEvent> {
        try {
            return nostr.getChatRoomMessages(roomId)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }

        return emptyList()
    }

    fun chatRoomConnect(roomId: Long) {
        viewModelScope.launch {
            try {
                val room = getChatRoom(roomId)
                val members = room.members

                nostr.chatRoomConnect(members.toList())
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun sendMessage(roomId: Long, message: String, replies: List<EventId> = emptyList()) {
        viewModelScope.launch {
            try {
                val room = getChatRoom(roomId)
                nostr.sendMessage(
                    to = room.members.toList(),
                    content = message,
                    subject = room.subject,
                    replies = replies,
                    onNewMessage = { event ->
                        viewModelScope.launch {
                            _newEvents.emit(event)
                        }
                    }
                )
            } catch (e: Exception) {
                showError("Error: ${e.message}")
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

fun PublicKey.short(): String {
    val bech32 = toBech32()
    return bech32.substring(0, 6) + "..." + bech32.substring(bech32.length - 4)
}
