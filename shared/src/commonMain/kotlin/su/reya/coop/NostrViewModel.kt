package su.reya.coop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Tag
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

    private val _contactList = MutableStateFlow<Set<PublicKey>>(emptySet())
    val contactList = _contactList.asStateFlow()

    private val _isPartialProcessedGiftWrap = MutableStateFlow(false)
    val isPartialProcessedGiftWrap = _isPartialProcessedGiftWrap.asStateFlow()

    private val _isRelayListEmpty = MutableStateFlow(false)
    val isRelayListEmpty = _isRelayListEmpty.asStateFlow()

    private val _newEvents = MutableSharedFlow<UnsignedEvent>(extraBufferCapacity = 100)
    val newEvents = _newEvents.asSharedFlow()

    private val _sentReports = MutableStateFlow<Map<EventId, List<RelayUrl>>>(emptyMap())
    val sentReport = _sentReports.asSharedFlow()

    private val _errorEvents = Channel<String>(Channel.BUFFERED)
    val errorEvents = _errorEvents.receiveAsFlow()

    private val _metadataStore = mutableMapOf<PublicKey, MutableStateFlow<Metadata?>>()
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)
    private val seenPublicKeys = mutableSetOf<PublicKey>()

    init {
        startNotificationHandler()
        startMetadataBatchHandler()
        getCacheMetadata()
        login()
        observeSignerAndCheckRelays()
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

    private fun showError(message: String) {
        viewModelScope.launch {
            _errorEvents.send(message)
            if (isCreating.value) _isCreating.value = false
        }
    }

    private fun startNotificationHandler() {
        viewModelScope.launch {
            // Wait until the client is ready
            nostr.waitUntilInitialized()

            nostr.handleNotifications(
                onMetadataUpdate = { pubkey, metadata ->
                    updateMetadata(pubkey, metadata)
                },
                onContactListUpdate = { contactList ->
                    _contactList.value = contactList.toSet()
                },
                onSubscriptionClose = {
                    getChatRooms()

                    if (!_isPartialProcessedGiftWrap.value) {
                        _isPartialProcessedGiftWrap.value = true
                    }
                },
                onNewMessage = { event ->
                    viewModelScope.launch {
                        _newEvents.emit(event)
                    }
                },
            )
        }
    }

    private fun startMetadataBatchHandler() {
        viewModelScope.launch {
            // Wait until the client is ready
            nostr.waitUntilInitialized()

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

    private fun getCacheMetadata() {
        viewModelScope.launch {
            // Wait until the client is ready
            nostr.waitUntilInitialized()

            val results = nostr.getAllCacheMetadata()
            results.forEach { (pubkey, metadata) ->
                updateMetadata(pubkey, metadata)
                seenPublicKeys.add(pubkey)
            }
        }
    }

    private fun login() {
        viewModelScope.launch {
            // Wait until the client is ready
            nostr.waitUntilInitialized()

            // Get user's signer secret
            val secret = secretStore.get("user_signer")

            // If no secret is found, show onboarding screen
            when (secret) {
                null -> {
                    _emptySecret.value = true
                    return@launch
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
                    val remote =
                        NostrConnect(uri = bunker, appKeys = appKeys, timeout = timeout, null)
                    nostr.setSigner(remote)
                } catch (e: Exception) {
                    showError("Error: ${e.message}")
                }
            } else {
                throw IllegalArgumentException("Invalid secret format: $secret")
            }
        }
    }

    private fun observeSignerAndCheckRelays() {
        viewModelScope.launch {
            while (true) {
                val pubkey = nostr.signer.currentUser

                if (pubkey != null) {
                    delay(3000)
                    val relays = nostr.getMsgRelays(pubkey)
                    if (relays.isEmpty()) {
                        _isRelayListEmpty.value = true
                    }
                    break
                }

                delay(1000)
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

    private fun updateMetadata(pubkey: PublicKey, metadata: Metadata) {
        _metadataStore.getOrPut(pubkey) { MutableStateFlow(null) }.value = metadata
    }

    fun getMetadata(pubkey: PublicKey): StateFlow<Metadata?> {
        val flow = _metadataStore.getOrPut(pubkey) { MutableStateFlow(null) }
        if (flow.value == null) {
            requestMetadata(pubkey)
        }
        return flow.asStateFlow()
    }

    fun currentUser(): PublicKey? {
        return nostr.signer.currentUser
    }

    fun logout() {
        viewModelScope.launch {
            secretStore.clear("user_signer")
            nostr.signer.switch(Keys.generate())
            _emptySecret.value = true
        }
    }

    fun dismissRelayWarning() {
        _isRelayListEmpty.value = false
    }

    private suspend fun getOrInitAppKeys(): Keys {
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
        bio: String?,
        picture: ByteArray?,
        contentType: String? = null
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
                nostr.createIdentity(keys = keys, name = name, bio, picture = avatarUrl)

                // Save secret to the secret storage
                secretStore.set("user_signer", secret)

                // Set an empty secret state
                _emptySecret.value = false
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    suspend fun verifyIdentity(secret: String): PublicKey? {
        if (secret.startsWith("nsec1")) {
            val keys = Keys.parse(secret)
            return keys.publicKey()
        } else if (secret.startsWith("bunker://")) {
            val appKeys = getOrInitAppKeys()
            val bunker = NostrConnectUri.parse(secret)
            val timeout = Duration.parse("50s") // 50 seconds timeout
            val remote = NostrConnect(uri = bunker, appKeys, timeout, null)

            // Show toast to ask user to approve the connection
            showError("Please approve the connection.")

            return remote.getPublicKeyAsync()
        } else {
            throw IllegalArgumentException("Invalid secret: $secret")
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

    suspend fun useDefaultMsgRelayList() {
        try {
            val defaultRelays = nostr.getDefaultMsgRelayList()
            nostr.setMsgRelays(defaultRelays)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    fun createChatRoom(to: List<PublicKey>): Long {
        if (nostr.signer.currentUser == null) throw IllegalStateException("User not signed in")
        if (to.isEmpty()) throw IllegalArgumentException("At least one recipient is required")

        // Construct the rumor event
        val rumor = EventBuilder
            .privateMsgRumor(to.first(), "")
            .tags(to.map { Tag.publicKey(it) })
            .build(nostr.signer.currentUser!!)

        // Create a room from the rumor event
        val room = Room.new(rumor, nostr.signer.currentUser!!)
        _chatRooms.value += room

        return room.id
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

    suspend fun refreshChatRooms() {
        try {
            _chatRooms.value = nostr.getChatRooms() ?: emptySet()
        } catch (e: Exception) {
            showError("Error: ${e.message}")
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

    suspend fun chatRoomConnect(roomId: Long): Map<PublicKey, List<RelayUrl>> {
        val room = getChatRoom(roomId)
        val members = room.members

        return runCatching {
            nostr.chatRoomConnect(members.toList())
        }.getOrElse { e ->
            showError("Error: ${e.message}")
            members.associateWith { emptyList<RelayUrl>() }
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
                    onRumorCreated = { event ->
                        updateRoomList(roomId, event)
                        viewModelScope.launch { _newEvents.emit(event) }
                    },
                )
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun isMessageSent(id: EventId): Boolean {
        val giftWrapId = nostr.rumorMap[id]

        if (giftWrapId != null) {
            val isSent = nostr.sentEvents[giftWrapId]?.isNotEmpty() ?: false
            return isSent
        } else {
            return false
        }
    }

    private fun updateRoomList(roomId: Long, newMessage: UnsignedEvent) {
        _chatRooms.value = _chatRooms.value.map { room ->
            if (room.id == roomId) {
                room.copy(lastMessage = newMessage.content(), createdAt = newMessage.createdAt())
            } else {
                room
            }
        }.toSet()
    }

    suspend fun searchByAddress(query: String): PublicKey? {
        try {
            return nostr.searchByAddress(query)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
        return null
    }

    suspend fun searchByNostr(query: String): List<PublicKey> {
        try {
            return nostr.searchByNostr(query)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
        return emptyList()
    }
}

fun PublicKey.short(): String {
    val bech32 = toBech32()
    return bech32.substring(0, 6) + "..." + bech32.substring(bech32.length - 4)
}
