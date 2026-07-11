package su.reya.coop.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.PublicKey
import su.reya.coop.Profile
import su.reya.coop.nostr.Nostr
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class NostrViewModel(private val nostr: Nostr) : BaseViewModel() {
    private val profilesMutex = Mutex()
    private val profiles = mutableMapOf<PublicKey, MutableStateFlow<Profile?>>()
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)
    private val seenPublicKeys = mutableSetOf<PublicKey>()

    init {
        // Launch continuous background observers
        viewModelScope.launch { runObserver() }
        viewModelScope.launch { runMetadataBatching() }

        // Automatically reconnect bootstrap relays
        reconnect()

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

}
