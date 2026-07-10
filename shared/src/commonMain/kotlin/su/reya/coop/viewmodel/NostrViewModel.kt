package su.reya.coop.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import su.reya.coop.Profile
import su.reya.coop.nostr.Nostr
import su.reya.coop.repository.MediaRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

data class NostrAppState(
    val isBusy: Boolean = false,
)

class NostrViewModel(
    private val nostr: Nostr,
    private val mediaRepository: MediaRepository,
) : BaseViewModel() {
    private val _appState = MutableStateFlow(NostrAppState())
    val appState: StateFlow<NostrAppState> = _appState.asStateFlow()

    private val _contactList = MutableStateFlow<Set<PublicKey>>(emptySet())
    val contactList = _contactList.asStateFlow()

    private val profilesMutex = Mutex()
    private val profiles = mutableMapOf<PublicKey, MutableStateFlow<Profile?>>()
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)
    private val seenPublicKeys = mutableSetOf<PublicKey>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentUserProfile = nostr.signer.publicKeyFlow
        .flatMapLatest { pubkey ->
            if (pubkey != null) getMetadata(pubkey) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Launch continuous background observers
        viewModelScope.launch { runObserver() }
        viewModelScope.launch { runMetadataBatching() }

        // Automatically reconnect bootstrap relays
        reconnect()

        // Fetch metadata for the current user
        fetchUserMetadata()

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

    private suspend fun runMetadataBatching() {
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
            val cache = nostr.profiles.getAllCacheMetadata()

            profilesMutex.withLock {
                cache.forEach { (pubkey, metadata) ->
                    val profile = Profile(pubkey, metadata)
                    profiles.getOrPut(pubkey) { MutableStateFlow(null) }.value = profile
                    seenPublicKeys.add(pubkey)
                }
            }
        }
    }

    private fun fetchUserMetadata() {
        viewModelScope.launch {
            // Wait until the client is ready
            nostr.waitUntilInitialized()

            // Wait until a signer is explicitly set (which updates publicKeyFlow)
            val currentUser = nostr.signer.publicKeyFlow.filterNotNull().first()

            // Get all metadata for the current user
            nostr.profiles.getUserMetadata()
        }
    }

    private fun requestMetadata(pubkey: PublicKey) {
        if (seenPublicKeys.add(pubkey)) {
            metadataRequestChannel.trySend(pubkey)
        }
    }

    private suspend fun updateMetadata(pubkey: PublicKey, profile: Profile) {
        profilesMutex.withLock {
            profiles.getOrPut(pubkey) { MutableStateFlow(null) }.value = profile
        }
    }

    fun getMetadata(pubkey: PublicKey): StateFlow<Profile?> {
        val flow = profiles.getOrPut(pubkey) { MutableStateFlow(null) }
        if (flow.value == null) requestMetadata(pubkey)

        return flow.asStateFlow()
    }

    fun resetInternalState() {
        _contactList.value = emptySet()
    }

    fun updateProfile(
        name: String? = null,
        bio: String? = null,
        picture: ByteArray? = null,
        contentType: String? = null
    ) {
        viewModelScope.launch {
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
                val currentUser =
                    nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")

                // Update the metadata state after successfully published
                updateMetadata(currentUser, Profile(currentUser, newMetadata))

                // Update local state
                _appState.update { it.copy(isBusy = false) }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                _appState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun newContact(publicKey: PublicKey) {
        if (publicKey in contactList.value) return

        viewModelScope.launch {
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
    }

    fun addContact(address: String) {
        viewModelScope.launch {
            val pubkey = try {
                if (address.contains("@")) {
                    nostr.profiles.searchByAddress(address)
                } else {
                    PublicKey.parse(address)
                }
            } catch (e: Exception) {
                showError("Invalid contact address: ${e.message}")
                return@launch
            }

            newContact(pubkey)
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

    fun searchByAddress(query: String, onResult: (PublicKey?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(nostr.profiles.searchByAddress(query))
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                onResult(null)
            }
        }
    }

    fun searchByNostr(query: String, onResult: (List<PublicKey>) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(nostr.profiles.searchByNostr(query))
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                onResult(emptyList())
            }
        }
    }

    fun verifyActivity(pubkey: PublicKey, onResult: (Timestamp?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(nostr.profiles.verifyActivity(pubkey))
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                onResult(null)
            }
        }
    }

    fun verifyContact(pubkey: PublicKey, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(nostr.profiles.verifyContact(pubkey))
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                onResult(false)
            }
        }
    }

    fun mutualContacts(pubkey: PublicKey, onResult: (Set<PublicKey>) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(nostr.profiles.mutualContacts(pubkey))
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                onResult(emptySet())
            }
        }
    }
}
