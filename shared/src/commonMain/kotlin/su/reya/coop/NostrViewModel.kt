package su.reya.coop

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Tag
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.blossom.BlossomClient
import su.reya.coop.storage.SecretStorage
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NostrViewModel(
    private val nostr: Nostr,
    private val secretStore: SecretStorage,
    private val externalSignerHandler: ExternalSignerHandler? = null,
) : ViewModel() {
    private val _isNotificationBannerDismissed = MutableStateFlow(false)
    val isNotificationBannerDismissed = _isNotificationBannerDismissed.asStateFlow()

    private val _signerRequired = MutableStateFlow<Boolean?>(null)
    val signerRequired = _signerRequired.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _isPartialProcessedGiftWrap = MutableStateFlow(false)
    val isPartialProcessedGiftWrap = _isPartialProcessedGiftWrap.asStateFlow()

    private val _isRelayListEmpty = MutableStateFlow(false)
    val isRelayListEmpty = _isRelayListEmpty.asStateFlow()

    private val _chatRooms = MutableStateFlow<Set<Room>>(emptySet())
    val chatRooms = _chatRooms.asStateFlow()

    private val _contactList = MutableStateFlow<Set<PublicKey>>(emptySet())
    val contactList = _contactList.asStateFlow()

    private val _newEvents = MutableSharedFlow<UnsignedEvent>(extraBufferCapacity = 100)
    val newEvents = _newEvents.asSharedFlow()

    private val _sentReports = MutableSharedFlow<Map<EventId, List<RelayUrl>>>()
    val sentReport = _sentReports.asSharedFlow()

    private val _errorEvents = Channel<String>(Channel.BUFFERED)
    val errorEvents = _errorEvents.receiveAsFlow()

    private val _metadataStore = mutableMapOf<PublicKey, MutableStateFlow<Metadata?>>()
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)
    private val seenPublicKeys = mutableSetOf<PublicKey>()

    init {
        // Skip the splash screen if a user is already logged in
        if (nostr.signer.currentUser != null) {
            _signerRequired.value = false
        }

        // Check if the notification banner has been dismissed
        checkNotificationBannerDismissedStatus()

        // Check local stored secret (secret key or bunker)
        login()

        // Automatically reconnect bootstrap relays
        reconnect()

        // Observe the signer state and verify the relay list
        observeSignerAndCheckRelays()

        // Get all local stored metadata
        getCacheMetadata()
    }

    fun bindLifecycle(lifecycle: Lifecycle) {
        viewModelScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                coroutineScope {
                    launch { refreshChatRooms() }
                    launch { runObserver() }
                    launch { runMetadataBatching() }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()

        // Disconnect to all bootstrap relays
        viewModelScope.launch {
            withContext(NonCancellable) {
                nostr.disconnect()
            }
        }
    }

    private fun showError(message: String) {
        viewModelScope.launch {
            _errorEvents.send(message)
        }
    }

    private fun checkNotificationBannerDismissedStatus() {
        viewModelScope.launch {
            _isNotificationBannerDismissed.value =
                secretStore.get("notification_banner_dismissed") == "true"
        }
    }

    private fun reconnect() {
        viewModelScope.launch {
            nostr.waitUntilInitialized()
            nostr.reconnect()
        }
    }

    private suspend fun runObserver() = coroutineScope {
        // Observe new messages
        launch {
            nostr.newEvents.collect { event ->
                val roomId = event.roomId()
                val existingRoom = _chatRooms.value.firstOrNull { it.id == roomId }

                if (existingRoom == null) {
                    val currentUser = nostr.signer.currentUser
                    if (currentUser != null) {
                        val newRoom = Room.new(event, currentUser)
                        _chatRooms.update { (it + newRoom).sortedDescending().toSet() }
                    }
                } else {
                    updateRoomList(roomId, event)
                }

                _newEvents.emit(event)
            }
        }

        // Observe contact list updates
        launch {
            nostr.contactListUpdates.collect { contacts ->
                _contactList.value = contacts.toSet()
            }
        }

        // Observe metadata updates
        launch {
            nostr.metadataUpdates.collect { (pubkey, metadata) ->
                updateMetadata(pubkey, metadata)
            }
        }

        // Observes subscription close
        launch {
            nostr.subscriptionClosed.collect {
                getChatRooms()
                _isPartialProcessedGiftWrap.value = true
            }
        }
    }

    private suspend fun runMetadataBatching() = coroutineScope {
        // Wait until the client is ready
        nostr.waitUntilInitialized()

        val batch = mutableSetOf<PublicKey>()
        val timeout = 500L // 500ms timeout for batching

        while (true) {
            val firstKey = metadataRequestChannel.receive()
            batch.add(firstKey)
            val lastFlushTime = Clock.System.now().toEpochMilliseconds()

            while (batch.isNotEmpty()) {
                val nextKey = withTimeoutOrNull(timeout.milliseconds) {
                    metadataRequestChannel.receive()
                }

                // Only add the key if it's not null
                if (nextKey != null) batch.add(nextKey)

                // Get current time
                val now = Clock.System.now().toEpochMilliseconds()

                // Check if the batch is full or timeout has passed
                if (batch.size >= 10 || (now - lastFlushTime) >= timeout || nextKey == null) {
                    val keysToRequest = batch.toList()
                    batch.clear()

                    nostr.fetchMetadataBatch(keysToRequest)
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
                // Update the metadata state
                updateMetadata(pubkey, metadata)
                // Update seenPublicKeys to avoid duplicate requests
                seenPublicKeys.add(pubkey)
            }
        }
    }

    private fun login() {
        viewModelScope.launch {
            try {
                val secret = withTimeoutOrNull(3.seconds) {
                    secretStore.get("user_signer")
                }

                if (secret == null) {
                    _signerRequired.value = true
                    return@launch
                }

                runCatching {
                    val signer = createSigner(secret)
                    nostr.setSigner(signer)
                }.onSuccess {
                    _signerRequired.value = false
                }.onFailure { e ->
                    showError("Login failed: ${e.message}")
                    _signerRequired.value = true
                }
            } catch (e: Exception) {
                showError("Login failed: ${e.message}")
                _signerRequired.value = true
            }
        }
    }

    private fun observeSignerAndCheckRelays() {
        viewModelScope.launch {
            while (true) {
                val pubkey = nostr.signer.currentUser

                if (pubkey != null) {
                    // Get chat rooms
                    val rooms = nostr.getChatRooms() ?: emptySet()
                    if (rooms.isNotEmpty()) {
                        mergeChatRooms(rooms)
                        _isPartialProcessedGiftWrap.value = true
                    }

                    // Get all metadata for the current user
                    nostr.getUserMetadata()

                    // Small delay to ensure all relays are connected
                    delay(2.seconds)

                    // Check if the relay list is empty
                    val relays = nostr.getMsgRelays(pubkey)
                    if (relays.isEmpty()) _isRelayListEmpty.value = true

                    break
                }

                delay(500.milliseconds)
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
            _signerRequired.value = true
        }
    }

    fun dismissNotificationBanner() {
        viewModelScope.launch {
            secretStore.set("notification_banner_dismissed", "true")
            _isNotificationBannerDismissed.value = true
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

    private suspend fun blossomUpload(file: ByteArray, contentType: String): String? {
        try {
            // Upload picture to Blossom
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
                file = file,
                contentType = contentType,
                signer = nostr.signer.get()
            )

            return descriptor?.url
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            return null
        }
    }

    suspend fun updateProfile(
        name: String? = null,
        bio: String? = null,
        picture: ByteArray? = null,
        contentType: String? = null
    ) {
        _isBusy.value = true
        try {
            val avatarUrl = picture?.let { blossomUpload(it, contentType ?: "image/jpeg") }
            val newMetadata = nostr.updateProfile(name, bio, avatarUrl)
            // Update the metadata state after successfully published
            updateMetadata(nostr.signer.currentUser!!, newMetadata)
            // Update local state
            _isBusy.value = false
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun createIdentity(
        name: String,
        bio: String?,
        picture: ByteArray?,
        contentType: String? = null
    ) {
        _isBusy.value = true

        val keys = Keys.generate()
        val secret = keys.secretKey().toBech32()

        try {
            val avatarUrl = picture?.let { blossomUpload(it, contentType ?: "image/jpeg") }
            // Create identity
            nostr.createIdentity(keys = keys, name = name, bio, picture = avatarUrl)
            // Persist the secret in the secret storage
            secretStore.set("user_signer", secret)
            // Update local states
            _isBusy.value = false
            _signerRequired.value = false
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    private suspend fun createSigner(secret: String): AsyncNostrSigner {
        return when {
            secret.startsWith("nsec1") -> Keys.parse(secret)

            secret.startsWith("bunker://") -> {
                val appKeys = getOrInitAppKeys()
                val bunker = NostrConnectUri.parse(secret)
                val timeout = 50.seconds // or Duration.parse("50s")
                NostrConnect(uri = bunker, appKeys, timeout, null)
            }

            secret.startsWith("nip55://") -> {
                val handler = externalSignerHandler
                    ?: throw IllegalStateException("External signer not available on this platform")

                // Format: nip55://packageName/hexPubkey
                val parts = secret.removePrefix("nip55://").split("/", limit = 2)
                val packageName = parts[0]
                val pubkey = PublicKey.parse(parts[1])

                handler.setPackageName(packageName)
                ExternalSignerProxy(handler, pubkey)
            }

            else -> throw IllegalArgumentException("Invalid secret format")
        }
    }

    suspend fun verifyIdentity(secret: String): PublicKey? {
        try {
            val signer = createSigner(secret)
            if (secret.startsWith("bunker://")) {
                showError("Please approve the connection.")
            }
            return signer.getPublicKeyAsync()
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            return null
        }
    }

    suspend fun importIdentity(secret: String) {
        _isBusy.value = true
        try {
            val signer = createSigner(secret)
            // Update signer
            nostr.setSigner(signer)
            // Persist the secret in the secret storage
            secretStore.set("user_signer", secret)
            // Update local states
            _signerRequired.value = false
            _isBusy.value = false
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun connectExternalSigner() {
        val handler = externalSignerHandler ?: throw IllegalStateException("Signer not available")
        _isBusy.value = true
        try {
            val permissions = SignerPermissions.toJson(
                listOf(
                    SignerPermissions.signEvent(0),
                    SignerPermissions.signEvent(3),
                    SignerPermissions.signEvent(10000),
                    SignerPermissions.signEvent(10050),
                    SignerPermissions.signEvent(10063),
                    SignerPermissions.signEvent(22242),
                    SignerPermissions.signEvent(30030),
                    SignerPermissions.signEvent(30315),
                    SignerPermissions.nip44Encrypt(),
                    SignerPermissions.nip44Decrypt(),
                )
            )

            val result = handler.getPublicKey(permissions) ?: throw Exception("Rejected")
            val signer = ExternalSignerProxy(handler, result.pubkey)

            // Update signer
            nostr.setSigner(signer)
            // Store the signer in the secret storage
            secretStore.set("user_signer", "nip55://${result.packageName}/${result.pubkey.toHex()}")
            // Update local states
            _signerRequired.value = false
            _isBusy.value = false
        } catch (e: Exception) {
            throw Exception("Notice: ${e.message}")
        }
    }

    fun isExternalSignerAvailable(): Boolean {
        return externalSignerHandler?.isAvailable() == true
    }

    suspend fun refetchMsgRelays(pubkey: PublicKey) {
        val relays = nostr.fetchMsgRelays(pubkey)
        if (relays.isNotEmpty()) dismissRelayWarning()
    }

    suspend fun useDefaultMsgRelayList() {
        try {
            val defaultRelays = nostr.getDefaultMsgRelayList()
            nostr.setMsgRelays(defaultRelays)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun currentUserRelayList(): Map<RelayUrl, RelayMetadata?> {
        try {
            return nostr.getRelayList(nostr.signer.currentUser!!)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            return emptyMap()
        }
    }

    suspend fun addInboxRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserRelayList().toMutableMap()
            relays[relayUrl] = RelayMetadata.WRITE

            nostr.setRelaylist(relays)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun addOutboxRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserRelayList().toMutableMap()
            relays[relayUrl] = RelayMetadata.READ

            nostr.setRelaylist(relays)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun removeRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserRelayList().toMutableMap()
            relays.remove(relayUrl)

            nostr.setRelaylist(relays)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun currentUserMsgRelayList(): List<RelayUrl> {
        try {
            return nostr.getMsgRelays(nostr.signer.currentUser!!)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            return emptyList()
        }
    }

    suspend fun addMsgRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserMsgRelayList().toMutableSet()
            relays.add(relayUrl)

            nostr.setMsgRelays(relays.toList())
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun removeMsgRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserMsgRelayList().toMutableSet()
            relays.remove(relayUrl)

            nostr.setMsgRelays(relays.toList())
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    fun createChatRoom(to: List<PublicKey>): Long {
        try {
            if (nostr.signer.currentUser == null) throw IllegalStateException("User not signed in")
            if (to.isEmpty()) throw IllegalArgumentException("At least one recipient is required")

            val currentUser = nostr.signer.currentUser!!

            // Construct the rumor event
            val rumor = EventBuilder(Kind.fromStd(KindStandard.PRIVATE_DIRECT_MESSAGE), "")
                .tags(to.map { Tag.publicKey(it) })
                .finalizeUnsigned(currentUser)

            // Check if the room already exists
            val id = rumor.roomId()
            val existingRoom = _chatRooms.value.firstOrNull { it.id == id }

            // If the room already exists, return its ID
            if (existingRoom != null) {
                return existingRoom.id
            }

            // Create a room from the rumor event
            val room = Room.new(rumor, currentUser)

            // Update the chat rooms state
            _chatRooms.update { currentRooms ->
                (currentRooms + room).sortedDescending().toSet()
            }

            return room.id
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to create room: ${e.message}")
        }
    }

    fun getChatRoom(id: Long): Room? {
        return chatRooms.value.firstOrNull { it.id == id }
    }

    private fun mergeChatRooms(rooms: Set<Room>) {
        _chatRooms.update { currentRooms ->
            val merged = currentRooms.associateBy { it.id }.toMutableMap()
            // Add or update rooms from the database
            rooms.forEach { room ->
                merged[room.id] = room
            }
            // Return as a sorted set to maintain UI consistency
            merged.values.sortedDescending().toSet()
        }
    }

    fun getChatRooms() {
        viewModelScope.launch {
            val rooms = nostr.getChatRooms() ?: emptySet()
            mergeChatRooms(rooms)
        }
    }

    suspend fun refreshChatRooms() {
        try {
            val rooms = nostr.getChatRooms() ?: emptySet()
            mergeChatRooms(rooms)
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

    fun chatRoomConnect(roomId: Long) {
        viewModelScope.launch {
            try {
                val room = getChatRoom(roomId) ?: throw IllegalArgumentException("Room not found")
                val members = room.members

                nostr.chatRoomConnect(members.toList())
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun sendMessage(roomId: Long, message: String, replies: List<EventId> = emptyList()) {
        if (message.isEmpty()) {
            showError("Message cannot be empty")
        }
        viewModelScope.launch {
            try {
                val room = getChatRoom(roomId) ?: throw IllegalArgumentException("Room not found")
                nostr.sendMessage(
                    to = room.members,
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
        _chatRooms.update { currentRooms ->
            currentRooms.map { room ->
                if (room.id == roomId) {
                    room.copy(
                        lastMessage = newMessage.content(),
                        createdAt = newMessage.createdAt()
                    )
                } else {
                    room
                }
            }.sortedDescending().toSet()
        }
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
