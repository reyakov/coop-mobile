package su.reya.coop.viewmodel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.PublicKey
import su.reya.coop.Profile
import su.reya.coop.nostr.Nostr
import kotlin.time.Duration.Companion.milliseconds

class ProfileCache(
    private val nostr: Nostr,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ErrorHost by createErrorHost() {
    private val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)
    private val profiles = MutableStateFlow<Map<PublicKey, MutableStateFlow<Profile?>>>(emptyMap())
    private val seenPublicKeys = MutableStateFlow<Set<PublicKey>>(emptySet())
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                runObserver()
            } catch (e: Exception) {
                showError("Metadata observer failed: ${e.message}")
            }
        }
        scope.launch {
            try {
                runMetadataBatching()
            } catch (e: Exception) {
                showError("Metadata batching failed: ${e.message}")
            }
        }
        scope.launch {
            try {
                nostr.waitUntilInitialized()
                getCachedMetadata()
            } catch (e: Exception) {
                showError("Failed to load initial cache: ${e.message}")
            }
        }
    }

    private suspend fun runObserver() {
        nostr.profiles.metadataUpdates.collect { (pubkey, metadata) ->
            updateMetadata(pubkey, Profile(pubkey, metadata))
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

            try {
                nostr.profiles.fetchMetadataBatch(batch.toList())
            } catch (e: Exception) {
                // Allow these keys to be requested again since the fetch failed
                seenPublicKeys.update { it - batch }
                println("Failed to fetch metadata batch: ${e.message}")
            }
        }
    }

    private suspend fun getCachedMetadata() {
        val cache = nostr.profiles.getAllCacheMetadata()
        cache.forEach { (pubkey, metadata) ->
            updateMetadata(pubkey, Profile(pubkey, metadata))
        }
    }

    private fun requestMetadata(pubkey: PublicKey) {
        var added = false
        seenPublicKeys.update { current ->
            if (current.contains(pubkey)) {
                added = false
                current
            } else {
                added = true
                current + pubkey
            }
        }
        if (added) metadataRequestChannel.trySend(pubkey)
    }

    private fun updateMetadata(pubkey: PublicKey, profile: Profile) {
        profiles.update { current ->
            val flow = current[pubkey] ?: MutableStateFlow<Profile?>(null)
            flow.value = profile
            if (current.containsKey(pubkey)) current else current + (pubkey to flow)
        }
        seenPublicKeys.update { it + pubkey }
    }

    fun getMetadata(pubkey: PublicKey): StateFlow<Profile?> {
        val currentMap = profiles.value
        val existingFlow = currentMap[pubkey]

        if (existingFlow != null) {
            if (existingFlow.value == null) requestMetadata(pubkey)
            return existingFlow.asStateFlow()
        }

        val newFlow = MutableStateFlow<Profile?>(null)
        var resultFlow = newFlow

        profiles.update { prev ->
            if (prev.containsKey(pubkey)) {
                resultFlow = prev[pubkey]!!
                prev
            } else {
                prev + (pubkey to newFlow)
            }
        }

        if (resultFlow.value == null) requestMetadata(pubkey)

        return resultFlow.asStateFlow()
    }
}
