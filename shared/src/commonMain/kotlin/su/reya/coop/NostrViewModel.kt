package su.reya.coop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.blossom.BlossomClient
import su.reya.coop.nostr.ExternalSignerHandler
import su.reya.coop.nostr.ExternalSignerProxy
import su.reya.coop.nostr.Nostr
import su.reya.coop.nostr.SignerPermissions
import su.reya.coop.storage.SecretStorage
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class NostrAppState(
    val isBusy: Boolean = false,
    val isRelayListEmpty: Boolean = false,
    val isSyncing: Boolean = false,
    val isPartialProcessedGiftWrap: Boolean = false,
    val isNotificationBannerDismissed: Boolean = false,
    val signerRequired: Boolean? = null,
)

class NostrViewModel(
    private val nostr: Nostr,
    private val secretStore: SecretStorage,
    private val externalSignerHandler: ExternalSignerHandler? = null,
) : ViewModel() {
    companion object {
        private const val KEY_USER_SIGNER = "user_signer"
        private const val KEY_APP_KEYS = "app_keys"
        private const val KEY_BANNER_DISMISSED = "notification_banner_dismissed"
    }

    private val backgroundWorkTrigger = flow {
        coroutineScope {
            val observerJob = launch { runObserver() }
            val batchingJob = launch { runMetadataBatching() }
            try {
                emit(Unit)
                awaitCancellation()
            } finally {
                observerJob.cancel()
                batchingJob.cancel()
            }
        }
    }

    private val _appState = MutableStateFlow(NostrAppState())
    val appState: StateFlow<NostrAppState> = combine(
        _appState,
        nostr.messages.messageSyncState.map { it.isSyncing },
        backgroundWorkTrigger
    ) { state, isSyncing, _ ->
        state.copy(isSyncing = isSyncing)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NostrAppState()
    )

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

    private val profilesMutex = Mutex()
    private val profiles = mutableMapOf<PublicKey, MutableStateFlow<Profile?>>()
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)
    private val seenPublicKeys = mutableSetOf<PublicKey>()

    val isNotificationBannerDismissed = appState.map { it.isNotificationBannerDismissed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val signerRequired = appState.map { it.signerRequired }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val isBusy = appState.map { it.isBusy }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isPartialProcessedGiftWrap = appState.map { it.isPartialProcessedGiftWrap }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isRelayListEmpty = appState.map { it.isRelayListEmpty }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isSyncing = appState.map { it.isSyncing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentUserProfile = nostr.signer.publicKeyFlow
        .flatMapLatest { pubkey ->
            if (pubkey != null) getMetadata(pubkey) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
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
            val dismissed = secretStore.get(KEY_BANNER_DISMISSED) == "true"
            _appState.update { it.copy(isNotificationBannerDismissed = dismissed) }
        }
    }

    private fun reconnect() {
        viewModelScope.launch {
            nostr.waitUntilInitialized()
            nostr.reconnect()
        }
    }

    private suspend fun runObserver() = coroutineScope {
        // Observe message sync progress
        launch {
            nostr.messages.messageSyncState.collect { state ->
                // When at least some messages are processed, allow UI to show the list
                if (state.processedCount > 0) {
                    _appState.update { it.copy(isPartialProcessedGiftWrap = true) }
                }

                // Refresh UI every 10 messages OR when sync is fully done
                if (state.processedCount % 10 == 0 || !state.isSyncing) {
                    refreshChatRooms()
                }
            }
        }

        // Observe new messages
        launch {
            nostr.newEvents.collect { event ->
                val roomId = event.roomId()
                val existingRoom = _chatRooms.value.firstOrNull { it.id == roomId }

                if (existingRoom == null) {
                    val currentUser = nostr.signer.getPublicKeyAsync() ?: return@collect
                    val newRoom = Room.new(event, currentUser)
                    _chatRooms.update { (it + newRoom).sortedDescending().toSet() }
                } else {
                    updateRoomList(roomId, event)
                }

                _newEvents.emit(event)
            }
        }

        // Observe contact list updates
        launch {
            nostr.profiles.contactListUpdates.collect { contacts ->
                _contactList.value = contacts.toSet()
            }
        }

        // Observe metadata updates
        launch {
            nostr.profiles.metadataUpdates.collect { (pubkey, metadata) ->
                updateMetadata(pubkey, Profile(pubkey, metadata))
            }
        }
    }

    private suspend fun runMetadataBatching() = coroutineScope {
        // Wait until the client is ready
        nostr.waitUntilInitialized()

        val batch = mutableSetOf<PublicKey>()
        val timeout = 500L // 500ms timeout for batching

        while (true) {
            // Get the first pubkey
            val firstKey = metadataRequestChannel.receive()
            batch.add(firstKey)

            // Get current time
            val lastFlushTime = Clock.System.now().toEpochMilliseconds()

            while (batch.isNotEmpty()) {
                // Get the next pubkey
                val nextKey = withTimeoutOrNull(timeout.milliseconds) {
                    metadataRequestChannel.receive()
                }

                // Only add the pubkey if it's not null
                if (nextKey != null) batch.add(nextKey)

                // Get current time
                val now = Clock.System.now().toEpochMilliseconds()

                // Check if the batch is full or timeout has passed
                if (batch.size >= 10 || (now - lastFlushTime) >= timeout || nextKey == null) {
                    val keysToRequest = batch.toList()
                    batch.clear()

                    nostr.profiles.fetchMetadataBatch(keysToRequest)
                }
            }
        }
    }

    private fun getCacheMetadata() {
        viewModelScope.launch {
            // Wait until the client is ready
            nostr.waitUntilInitialized()

            val results = nostr.profiles.getAllCacheMetadata()
            results.forEach { (pubkey, metadata) ->
                // Update the metadata state
                updateMetadata(pubkey, Profile(pubkey, metadata))
                // Update seenPublicKeys to avoid duplicate requests
                seenPublicKeys.add(pubkey)
            }
        }
    }

    private fun login() {
        viewModelScope.launch {
            try {
                val secret = withTimeoutOrNull(3.seconds) {
                    secretStore.get(KEY_USER_SIGNER)
                }

                if (secret == null) {
                    _appState.update { it.copy(signerRequired = true) }
                    return@launch
                }

                runCatching {
                    val signer = createSigner(secret)
                    nostr.setSigner(signer)
                }.onSuccess {
                    _appState.update { it.copy(signerRequired = false) }
                }.onFailure { e ->
                    showError("Login failed: ${e.message}")
                    _appState.update { it.copy(signerRequired = true) }
                }
            } catch (e: Exception) {
                showError("Login failed: ${e.message}")
                _appState.update { it.copy(signerRequired = true) }
            }
        }
    }

    private fun observeSignerAndCheckRelays() {
        viewModelScope.launch {
            while (true) {
                val currentUser = nostr.signer.getPublicKeyAsync()

                if (currentUser != null) {
                    // Get chat rooms
                    val rooms = nostr.messages.getChatRooms() ?: emptySet()
                    if (rooms.isNotEmpty()) {
                        mergeChatRooms(rooms)
                        _appState.update { it.copy(isPartialProcessedGiftWrap = true) }
                    }

                    // Get all metadata for the current user
                    nostr.profiles.getUserMetadata()

                    // Small delay to ensure all relays are connected
                    delay(2.seconds)

                    // Check if the relay list is empty
                    val relays = nostr.relays.getMsgRelays(currentUser)
                    if (relays.isEmpty()) _appState.update { it.copy(isRelayListEmpty = true) }

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

    private fun updateMetadata(pubkey: PublicKey, profile: Profile) {
        viewModelScope.launch {
            profilesMutex.withLock {
                profiles.getOrPut(pubkey) { MutableStateFlow(null) }.value = profile
            }
        }
    }

    fun getMetadata(pubkey: PublicKey): StateFlow<Profile?> {
        val flow = profiles.getOrPut(pubkey) { MutableStateFlow(null) }
        if (flow.value == null) requestMetadata(pubkey)

        return flow.asStateFlow()
    }

    fun logout() {
        viewModelScope.launch {
            try {
                _appState.update { it.copy(isBusy = true) }
                // Reset the nostr signer and prune the database
                nostr.signer.switch(Keys.generate())
                nostr.prune()
            } catch (e: Exception) {
                showError("Logout encountered an error: ${e.message}")
            } finally {
                // Clear credentials from persistent storage
                secretStore.clear(KEY_USER_SIGNER)
                secretStore.clear(KEY_BANNER_DISMISSED)

                // Reset all UI states
                resetInternalState()

                _appState.update { it.copy(isBusy = false, signerRequired = true) }
            }
        }
    }

    private fun resetInternalState() {
        _chatRooms.value = emptySet()
        _contactList.value = emptySet()
        _appState.update {
            it.copy(
                isPartialProcessedGiftWrap = false,
                isRelayListEmpty = false,
                isNotificationBannerDismissed = false
            )
        }
    }

    fun dismissNotificationBanner() {
        viewModelScope.launch {
            secretStore.set(KEY_BANNER_DISMISSED, "true")
            _appState.update { it.copy(isNotificationBannerDismissed = true) }
        }
    }

    fun dismissRelayWarning() {
        _appState.update { it.copy(isRelayListEmpty = false) }
    }

    private suspend fun getOrInitAppKeys(): Keys {
        val secret = secretStore.get(KEY_APP_KEYS)

        // If app keys are already stored, use them
        if (secret != null) {
            return Keys.parse(secret)
        }

        // Generate new app keys and save to the secret storage
        val keys = Keys.generate()
        secretStore.set(KEY_APP_KEYS, keys.secretKey().toBech32())

        return keys
    }

    suspend fun blossomUpload(file: ByteArray, contentType: String? = "image/jpeg"): String? {
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
        _appState.update { it.copy(isBusy = true) }
        try {
            val avatarUrl = picture?.let { blossomUpload(it, contentType ?: "image/jpeg") }
            val newMetadata = nostr.profiles.updateProfile(name, bio, avatarUrl)
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            // Update the metadata state after successfully published
            updateMetadata(currentUser, Profile(currentUser, newMetadata))
            // Update local state
            _appState.update { it.copy(isBusy = false) }
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
        _appState.update { it.copy(isBusy = true) }

        val keys = Keys.generate()
        val secret = keys.secretKey().toBech32()

        try {
            val avatarUrl = picture?.let { blossomUpload(it, contentType ?: "image/jpeg") }
            // Create identity
            nostr.profiles.createIdentity(keys = keys, name = name, bio, picture = avatarUrl)
            // Persist the secret in the secret storage
            secretStore.set(KEY_USER_SIGNER, secret)
            // Update local states
            _appState.update { it.copy(isBusy = false, signerRequired = false) }
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
        _appState.update { it.copy(isBusy = true) }
        try {
            val signer = createSigner(secret)
            // Update signer
            nostr.setSigner(signer)
            // Persist the secret in the secret storage
            secretStore.set(KEY_USER_SIGNER, secret)
            // Update local states
            _appState.update { it.copy(signerRequired = false, isBusy = false) }
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun connectExternalSigner() {
        val handler = externalSignerHandler ?: throw IllegalStateException("Signer not available")
        _appState.update { it.copy(isBusy = true) }
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
            secretStore.set(
                KEY_USER_SIGNER,
                "nip55://${result.packageName}/${result.pubkey.toHex()}"
            )
            // Update local states
            _appState.update { it.copy(signerRequired = false, isBusy = false) }
        } catch (e: Exception) {
            throw Exception("Notice: ${e.message}")
        }
    }

    fun isExternalSignerAvailable(): Boolean {
        return externalSignerHandler?.isAvailable() == true
    }

    suspend fun refetchMsgRelays() {
        val currentUser = nostr.signer.getPublicKeyAsync() ?: return
        val relays = nostr.relays.fetchMsgRelays(currentUser)

        if (relays.isNotEmpty()) dismissRelayWarning()
    }

    suspend fun useDefaultMsgRelayList() {
        try {
            val defaultRelays = nostr.relays.getDefaultMsgRelayList()
            nostr.relays.setMsgRelays(defaultRelays)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun currentUserRelayList(): Map<RelayUrl, RelayMetadata?> {
        try {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            return nostr.relays.getRelayList(currentUser)
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

            nostr.relays.setRelaylist(relays)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun addOutboxRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserRelayList().toMutableMap()
            relays[relayUrl] = RelayMetadata.READ

            nostr.relays.setRelaylist(relays)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun removeRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserRelayList().toMutableMap()
            relays.remove(relayUrl)

            nostr.relays.setRelaylist(relays)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun currentUserMsgRelayList(): List<RelayUrl> {
        try {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            return nostr.relays.getMsgRelays(currentUser)
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

            nostr.relays.setMsgRelays(relays.toList())
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun removeMsgRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserMsgRelayList().toMutableSet()
            relays.remove(relayUrl)

            nostr.relays.setMsgRelays(relays.toList())
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    private suspend fun newContact(publicKey: PublicKey) {
        if (publicKey in contactList.value) return

        try {
            val updated = contactList.value + publicKey
            // Publish new event
            nostr.profiles.setContactList(updated.toList())
            // Optimistic local update
            _contactList.update { it + publicKey }
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun addContact(address: String): Boolean {
        val pubkey = try {
            if (address.contains("@")) {
                nostr.profiles.searchByAddress(address)
            } else {
                PublicKey.parse(address)
            }
        } catch (e: Exception) {
            showError("Invalid contact address: ${e.message}")
            return false
        }

        return run {
            newContact(pubkey)
            true
        }
    }

    fun removeContact(publicKey: PublicKey) {
        viewModelScope.launch {
            if (publicKey !in contactList.value) return@launch

            try {
                val updated = contactList.value - publicKey
                // Publish new event
                nostr.profiles.setContactList(updated.toList())
                // Optimistic local update
                _contactList.update { it - publicKey }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun createChatRoom(to: List<PublicKey>): Long {
        try {
            if (to.isEmpty()) {
                throw IllegalArgumentException("At least one recipient is required")
            }

            // Get current user
            val currentUser = nostr.signer.publicKeyFlow.value
                ?: throw IllegalStateException("User not signed in")

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
            val rooms = nostr.messages.getChatRooms() ?: emptySet()
            mergeChatRooms(rooms)
        }
    }

    suspend fun refreshChatRooms() {
        try {
            val rooms = nostr.messages.getChatRooms() ?: emptySet()
            mergeChatRooms(rooms)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun getChatRoomMessages(roomId: Long): List<UnsignedEvent> {
        try {
            return nostr.messages.getChatRoomMessages(roomId)
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

                nostr.messages.chatRoomConnect(members.toList())
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
                nostr.messages.sendMessage(
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

    suspend fun sendFileMessage(
        roomId: Long,
        file: ByteArray?,
        contentType: String? = "image/jpeg",
        replies: List<EventId> = emptyList()
    ) {
        if (file == null) return

        try {
            val uri = blossomUpload(file, contentType)
                ?: throw IllegalArgumentException("Failed to upload file")

            sendMessage(roomId, uri, replies)
        } catch (e: Exception) {
            throw IllegalArgumentException("Error: ${e.message}")
        }
    }

    fun isMessageSent(id: EventId): Boolean {
        val giftWrapId = nostr.messages.rumorMap[id]

        if (giftWrapId != null) {
            val isSent = nostr.messages.sentEvents[giftWrapId]?.isNotEmpty() ?: false
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
            return nostr.profiles.searchByAddress(query)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
        return null
    }

    suspend fun searchByNostr(query: String): List<PublicKey> {
        try {
            return nostr.profiles.searchByNostr(query)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
        return emptyList()
    }

    suspend fun verifyActivity(pubkey: PublicKey): Timestamp? {
        return try {
            nostr.profiles.verifyActivity(pubkey)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            null
        }
    }

    suspend fun verifyContact(pubkey: PublicKey): Boolean {
        return try {
            nostr.profiles.verifyContact(pubkey)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            false
        }
    }

    suspend fun mutualContacts(pubkey: PublicKey): Set<PublicKey> {
        return try {
            nostr.profiles.mutualContacts(pubkey)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            setOf()
        }
    }
}

