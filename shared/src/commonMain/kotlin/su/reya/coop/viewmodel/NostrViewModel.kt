package su.reya.coop.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Timestamp
import su.reya.coop.Profile
import su.reya.coop.nostr.Nostr
import su.reya.coop.repository.MediaRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class NostrAppState(
    val isBusy: Boolean = false,
    val isRelayListEmpty: Boolean = false,
)

class NostrViewModel(private val nostr: Nostr) : BaseViewModel() {
    private val mediaRepository = MediaRepository()

    private val alwaysRunTasks = flow {
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
    val appState: StateFlow<NostrAppState> =
        combine(_appState, alwaysRunTasks) { state, _ -> state }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = NostrAppState()
        )

    private val _contactList = MutableStateFlow<Set<PublicKey>>(emptySet())
    val contactList = _contactList.asStateFlow()

    private val profilesMutex = Mutex()
    private val profiles = mutableMapOf<PublicKey, MutableStateFlow<Profile?>>()
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)
    private val seenPublicKeys = mutableSetOf<PublicKey>()

    val isBusy = appState.map { it.isBusy }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isRelayListEmpty = appState.map { it.isRelayListEmpty }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentUserProfile = nostr.signer.publicKeyFlow
        .flatMapLatest { pubkey ->
            if (pubkey != null) getMetadata(pubkey) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Automatically reconnect bootstrap relays
        reconnect()

        // Observe the signer state and verify the relay list
        observeSignerAndCheckRelays()

        // Get all local stored metadata
        getCacheMetadata()
    }

    private fun reconnect() {
        viewModelScope.launch {
            nostr.waitUntilInitialized()
            nostr.reconnect()
        }
    }

    private suspend fun runObserver() = coroutineScope {
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

    private fun observeSignerAndCheckRelays() {
        viewModelScope.launch {
            while (true) {
                val currentUser = nostr.signer.getPublicKeyAsync()

                if (currentUser != null) {
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

    fun resetInternalState() {
        _contactList.value = emptySet()
        _appState.update {
            it.copy(
                isRelayListEmpty = false,
            )
        }
    }

    fun dismissRelayWarning() {
        _appState.update { it.copy(isRelayListEmpty = false) }
    }

    suspend fun updateProfile(
        name: String? = null,
        bio: String? = null,
        picture: ByteArray? = null,
        contentType: String? = null
    ) {
        _appState.update { it.copy(isBusy = true) }
        try {
            val avatarUrl =
                picture?.let {
                    mediaRepository.blossomUpload(
                        nostr.signer.get(),
                        it,
                        contentType ?: "image/jpeg"
                    )
                }
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
