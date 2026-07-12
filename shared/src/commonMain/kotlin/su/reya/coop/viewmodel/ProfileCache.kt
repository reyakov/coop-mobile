package su.reya.coop.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * Application-level singleton cache for Nostr user profiles.
 *
 * Replaces [NostrViewModel] as a non-ViewModel component with its own lifecycle scope.
 * This is appropriate because profile caching is not screen-specific — it's a shared
 * concern used by every screen that displays user metadata.
 *
 * Long-running tasks ([runObserver], [runMetadataBatching]) run in a dedicated
 * [CoroutineScope] that outlives any individual screen, ensuring continuous operation
 * regardless of navigation.
 */
class ProfileCache(
    private val nostr: Nostr,
) : ErrorHost by createErrorHost() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val profilesMutex = Mutex()
    private val profiles = mutableMapOf<PublicKey, MutableStateFlow<Profile?>>()
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)
    private val seenPublicKeys = mutableSetOf<PublicKey>()

    init {
        scope.launch { runObserver() }
        scope.launch { runMetadataBatching() }
        scope.launch {
            nostr.waitUntilInitialized()
            loadCacheMetadata()
        }
    }

    private suspend fun runObserver() = coroutineScope {
        launch {
            nostr.profiles.metadataUpdates.collect { (pubkey, metadata) ->
                updateMetadata(pubkey, Profile(pubkey, metadata))
            }
        }
    }

    private suspend fun runMetadataBatching() {
        nostr.waitUntilInitialized()

        while (true) {
            val firstKey = metadataRequestChannel.receive()
            val batch = mutableSetOf(firstKey)

            while (batch.size < 10) {
                val nextKey =
                    withTimeoutOrNull(500.milliseconds) { metadataRequestChannel.receive() }
                        ?: break
                batch.add(nextKey)
            }

            nostr.profiles.fetchMetadataBatch(batch.toList())
        }
    }

    private suspend fun loadCacheMetadata() {
        val cache = nostr.profiles.getAllCacheMetadata()

        profilesMutex.withLock {
            cache.forEach { (pubkey, metadata) ->
                val profile = Profile(pubkey, metadata)
                profiles.getOrPut(pubkey) { MutableStateFlow(null) }.value = profile
                seenPublicKeys.add(pubkey)
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

    /**
     * Returns a [StateFlow] for the profile of the given [pubkey].
     * Triggers a metadata fetch if the profile is not yet cached.
     */
    fun getMetadata(pubkey: PublicKey): StateFlow<Profile?> {
        val flow = profiles.getOrPut(pubkey) { MutableStateFlow(null) }
        if (flow.value == null) requestMetadata(pubkey)
        return flow.asStateFlow()
    }
}
