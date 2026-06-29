package su.reya.coop.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import su.reya.coop.blossom.BlossomClient
import su.reya.coop.nostr.Nostr
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class ProfileViewModel(
    private val nostr: Nostr,
    private val appViewModel: AppViewModel
) : ViewModel() {
    private val _contactList = MutableStateFlow<Set<PublicKey>>(emptySet())
    val contactList = _contactList.asStateFlow()

    private val _metadataStore = mutableMapOf<PublicKey, MutableStateFlow<Metadata?>>()
    private val metadataRequestChannel = Channel<PublicKey>(Channel.UNLIMITED)
    private val seenPublicKeys = mutableSetOf<PublicKey>()

    init {
        getCacheMetadata()
    }

    suspend fun bindObservers() = coroutineScope {
        launch {
            nostr.profiles.contactListUpdates.collect { contacts ->
                _contactList.value = contacts.toSet()
            }
        }

        launch {
            nostr.profiles.metadataUpdates.collect { (pubkey, metadata) ->
                updateMetadata(pubkey, metadata)
            }
        }

        launch { runMetadataBatching() }
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

    private fun requestMetadata(pubkey: PublicKey) {
        if (seenPublicKeys.add(pubkey)) {
            viewModelScope.launch {
                metadataRequestChannel.send(pubkey)
            }
        }
    }

    private fun getCacheMetadata() {
        viewModelScope.launch {
            nostr.waitUntilInitialized()
            val results = nostr.profiles.getAllCacheMetadata()
            results.forEach { (pubkey, metadata) ->
                updateMetadata(pubkey, metadata)
                seenPublicKeys.add(pubkey)
            }
        }
    }

    private suspend fun runMetadataBatching() = coroutineScope {
        nostr.waitUntilInitialized()
        val batch = mutableSetOf<PublicKey>()
        val timeout = 500L

        while (true) {
            val firstKey = metadataRequestChannel.receive()
            batch.add(firstKey)
            val lastFlushTime = Clock.System.now().toEpochMilliseconds()

            while (batch.isNotEmpty()) {
                val nextKey = withTimeoutOrNull(timeout.milliseconds) {
                    metadataRequestChannel.receive()
                }
                if (nextKey != null) batch.add(nextKey)
                val now = Clock.System.now().toEpochMilliseconds()

                if (batch.size >= 10 || (now - lastFlushTime) >= timeout || nextKey == null) {
                    val keysToRequest = batch.toList()
                    batch.clear()
                    nostr.profiles.fetchMetadataBatch(keysToRequest)
                }
            }
        }
    }

    suspend fun blossomUpload(file: ByteArray, contentType: String?): String? {
        try {
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
            appViewModel.showError("Error: ${e.message}")
            return null
        }
    }

    suspend fun updateProfile(
        name: String? = null,
        bio: String? = null,
        picture: ByteArray? = null,
        contentType: String? = null
    ) {
        appViewModel.setBusy(true)
        try {
            val avatarUrl = picture?.let { blossomUpload(it, contentType ?: "image/jpeg") }
            val newMetadata = nostr.profiles.updateProfile(name, bio, avatarUrl)
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            updateMetadata(currentUser, newMetadata)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        } finally {
            appViewModel.setBusy(false)
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
            appViewModel.showError("Invalid contact address: ${e.message}")
            return false
        }

        if (pubkey in contactList.value) return true

        return try {
            val updated = contactList.value + pubkey
            nostr.profiles.setContactList(updated.toList())
            _contactList.update { it + pubkey }
            true
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
            false
        }
    }

    fun removeContact(publicKey: PublicKey) {
        viewModelScope.launch {
            if (publicKey !in contactList.value) return@launch
            try {
                val updated = contactList.value - publicKey
                nostr.profiles.setContactList(updated.toList())
                _contactList.update { it - publicKey }
            } catch (e: Exception) {
                appViewModel.showError("Error: ${e.message}")
            }
        }
    }

    suspend fun searchByAddress(query: String): PublicKey? {
        try {
            return nostr.profiles.searchByAddress(query)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        }
        return null
    }

    suspend fun searchByNostr(query: String): List<PublicKey> {
        try {
            return nostr.profiles.searchByNostr(query)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        }
        return emptyList()
    }

    suspend fun verifyActivity(pubkey: PublicKey): Timestamp? {
        return try {
            nostr.profiles.verifyActivity(pubkey)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
            null
        }
    }

    suspend fun verifyContact(pubkey: PublicKey): Boolean {
        return try {
            nostr.profiles.verifyContact(pubkey)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
            false
        }
    }

    suspend fun mutualContacts(pubkey: PublicKey): Set<PublicKey> {
        return try {
            nostr.profiles.mutualContacts(pubkey)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
            setOf()
        }
    }

    fun getUserMetadata() {
        viewModelScope.launch {
            nostr.profiles.getUserMetadata()
        }
    }
}
