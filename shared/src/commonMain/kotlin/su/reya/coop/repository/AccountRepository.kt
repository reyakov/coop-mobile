package su.reya.coop.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.EncryptedSecretKey
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Timestamp
import su.reya.coop.AppStorage
import su.reya.coop.Profile
import su.reya.coop.nostr.ExternalSignerHandler
import su.reya.coop.nostr.ExternalSignerProxy
import su.reya.coop.nostr.Nostr
import su.reya.coop.nostr.SignerPermissions
import su.reya.coop.viewmodel.ErrorHost
import su.reya.coop.viewmodel.createErrorHost
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class AccountState(
    val signerRequired: Boolean? = null,
    val isNotificationBannerDismissed: Boolean = false,
    val isImporting: Boolean = false,
    val isRelayListEmpty: Boolean = false,
    val contactList: Set<PublicKey> = emptySet(),
    val userRelayList: Map<RelayUrl, RelayMetadata?> = emptyMap(),
    val userMsgRelayList: List<RelayUrl> = emptyList(),
)

class AccountRepository(
    private val nostr: Nostr,
    private val storage: AppStorage,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val externalSignerHandler: ExternalSignerHandler? = null,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ErrorHost by createErrorHost() {
    companion object {
        private const val KEY_USER_SIGNER = "user_signer"
        private const val KEY_APP_KEYS = "app_keys"
        private const val KEY_BANNER_DISMISSED = "notification_banner_dismissed"
    }

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _isUpdatingProfile = MutableStateFlow(false)
    val isUpdatingProfile: StateFlow<Boolean> = _isUpdatingProfile.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentUserProfile: StateFlow<Profile?> = nostr.signer.publicKeyFlow
        .flatMapLatest { if (it != null) currentUserProfileFlow(it) else flowOf(null) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    init {
        checkNotificationBannerDismissedStatus()
        login()
        observeSignerState()
        observeContactList()
    }

    private fun observeSignerState() {
        scope.launch {
            nostr.signer.publicKeyFlow
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { pubkey ->
                    // Refresh metadata
                    nostr.profiles.getUserMetadata()

                    // Verify messaging relays exist (wait up to 10s for initial fetch)
                    val relays = withTimeoutOrNull(10.seconds) {
                        var list = nostr.relays.getMsgRelays(pubkey)
                        while (list.isEmpty()) {
                            delay(500.milliseconds)
                            list = nostr.relays.getMsgRelays(pubkey)
                        }
                        list
                    } ?: emptyList()

                    // Automatically update the warning state
                    _state.update { it.copy(isRelayListEmpty = relays.isEmpty()) }
                }
        }
    }

    private fun checkNotificationBannerDismissedStatus() {
        scope.launch {
            val dismissed = storage.get(KEY_BANNER_DISMISSED) == "true"
            _state.update { it.copy(isNotificationBannerDismissed = dismissed) }
        }
    }

    private fun login() {
        scope.launch {
            try {
                val secret = withTimeoutOrNull(5.seconds) {
                    storage.getSecret(KEY_USER_SIGNER)
                }

                if (secret == null) {
                    _state.update { it.copy(signerRequired = true) }
                    return@launch
                }

                runCatching {
                    val (signer, _) = createSigner(secret)
                    nostr.setSigner(signer)
                }.onSuccess {
                    _state.update { it.copy(signerRequired = false) }
                }.onFailure { e ->
                    showError("Login failed: ${e.message}")
                    _state.update { it.copy(signerRequired = true) }
                }
            } catch (e: Exception) {
                showError("Login failed: ${e.message}")
                _state.update { it.copy(signerRequired = true) }
            }
        }
    }

    fun logout(onLogout: () -> Unit = {}) {
        scope.launch {
            try {
                nostr.signer.switch(Keys.generate())
                nostr.prune()
            } catch (e: Exception) {
                showError("Logout encountered an error: ${e.message}")
            } finally {
                storage.clear(KEY_USER_SIGNER)
                storage.clear(KEY_BANNER_DISMISSED)
                onLogout()
                _state.update { it.copy(signerRequired = true) }
            }
        }
    }

    fun dismissNotificationBanner() {
        scope.launch {
            storage.set(KEY_BANNER_DISMISSED, "true")
            _state.update { it.copy(isNotificationBannerDismissed = true) }
        }
    }

    private suspend fun getOrInitAppKeys(): Keys = withContext(defaultDispatcher) {
        val secret = storage.getSecret(KEY_APP_KEYS)
        if (secret != null) return@withContext Keys.parse(secret)
        val keys = Keys.generate()
        storage.setSecret(KEY_APP_KEYS, keys.secretKey().toBech32())
        keys
    }

    private suspend fun createSigner(
        secret: String,
        password: String? = null
    ): Pair<AsyncNostrSigner, String?> = withContext(defaultDispatcher) {
        when {
            secret.startsWith("nsec1") -> Keys.parse(secret) to null

            secret.startsWith("ncryptsec1") -> {
                if (password == null) throw IllegalArgumentException("Password is required")
                val enc = EncryptedSecretKey.fromBech32(secret)
                val decrypted = enc.decrypt(password)
                val keys = Keys(decrypted)
                keys to keys.secretKey().toBech32()
            }

            secret.startsWith("bunker://") -> {
                val appKeys = getOrInitAppKeys()
                val bunker = NostrConnectUri.parse(secret)
                val timeout = 50.seconds
                NostrConnect(uri = bunker, appKeys, timeout, null) to null
            }

            secret.startsWith("nip55://") -> {
                val handler = externalSignerHandler ?: throw IllegalStateException("Not available")
                val parts = secret.removePrefix("nip55://").split("/", limit = 2)
                val packageName = parts[0]
                val pubkey = PublicKey.parse(parts[1])

                handler.setPackageName(packageName)
                ExternalSignerProxy(handler, pubkey) to null
            }

            else -> throw IllegalArgumentException("Invalid secret format")
        }
    }

    fun isExternalSignerAvailable(): Boolean {
        return externalSignerHandler?.isAvailable() == true
    }

    fun importIdentity(secret: String, password: String? = null) {
        scope.launch {
            _state.update { it.copy(isImporting = true) }
            try {
                val (signer, decryptedSecret) = createSigner(secret, password)

                nostr.setSigner(signer)

                storage.setSecret(KEY_USER_SIGNER, decryptedSecret ?: secret)
                _state.update { it.copy(signerRequired = false, isImporting = false) }
            } catch (e: Exception) {
                showError("Import failed: ${e.message}")
                _state.update { it.copy(isImporting = false) }
            }
        }
    }

    fun connectExternalSigner() {
        scope.launch {
            _state.update { it.copy(isImporting = true) }
            try {
                val handler =
                    externalSignerHandler ?: throw IllegalStateException("Signer not available")

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
                val uri = "nip55://${result.packageName}/${result.pubkey.toHex()}"

                nostr.setSigner(signer)

                storage.setSecret(KEY_USER_SIGNER, uri)
                _state.update { it.copy(signerRequired = false, isImporting = false) }
            } catch (e: Exception) {
                showError("External signer connection failed: ${e.message}")
                _state.update { it.copy(isImporting = false) }
            }
        }
    }

    fun createIdentity(
        name: String,
        bio: String?,
        picture: ByteArray?,
        contentType: String? = null
    ) {
        scope.launch {
            _state.update { it.copy(isImporting = true) }
            try {
                val keys = Keys.generate()
                val secret = keys.secretKey().toBech32()
                val avatarUrl = picture?.let {
                    mediaRepository.blossomUpload(keys, it, contentType ?: "image/jpeg")
                }

                nostr.profiles.createIdentity(
                    keys = keys,
                    name = name,
                    bio = bio,
                    picture = avatarUrl
                )

                storage.setSecret(KEY_USER_SIGNER, secret)
                _state.update { it.copy(signerRequired = false, isImporting = false) }
            } catch (e: Exception) {
                showError("Identity creation failed: ${e.message}")
                _state.update { it.copy(isImporting = false) }
            }
        }
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    private fun currentUserProfileFlow(pubkey: PublicKey) = merge(
        flow {
            nostr.waitUntilInitialized()
            val cached = nostr.profiles.getAllCacheMetadata()[pubkey]
            if (cached != null) emit(Profile(pubkey, cached))
            nostr.profiles.fetchMetadataBatch(listOf(pubkey))
        },
        nostr.profiles.metadataUpdates
            .filter { (p, _) -> p == pubkey }
            .map { (p, m) -> Profile(p, m) }
    )

    fun updateProfile(
        name: String? = null,
        bio: String? = null,
        picture: ByteArray? = null,
        contentType: String? = null
    ) {
        scope.launch {
            _isUpdatingProfile.value = true
            try {
                val avatarUrl = picture?.let {
                    mediaRepository.blossomUpload(
                        nostr.signer.get(),
                        it,
                        contentType ?: "image/jpeg"
                    )
                }
                nostr.profiles.updateProfile(name, bio, avatarUrl)
                _isUpdatingProfile.value = false
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                _isUpdatingProfile.value = false
            }
        }
    }

    private fun observeContactList() {
        scope.launch {
            nostr.waitUntilInitialized()
            nostr.profiles.contactListUpdates.collect { contacts ->
                _state.update { it.copy(contactList = contacts.toSet()) }
            }
        }
    }

    fun resetInternalState() {
        _state.update { it.copy(contactList = emptySet(), isRelayListEmpty = false) }
    }

    fun addContact(address: String) {
        scope.launch {
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

            if (pubkey in _state.value.contactList) return@launch

            try {
                val updated = _state.value.contactList + pubkey
                nostr.profiles.setContactList(updated.toList())
                _state.update { it.copy(contactList = it.contactList + pubkey) }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun removeContact(publicKey: PublicKey) {
        scope.launch {
            if (publicKey !in _state.value.contactList) return@launch

            try {
                val updated = _state.value.contactList - publicKey
                nostr.profiles.setContactList(updated.toList())
                _state.update { it.copy(contactList = it.contactList - publicKey) }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun searchByAddress(query: String, onResult: (PublicKey?) -> Unit) {
        scope.launch {
            try {
                onResult(nostr.profiles.searchByAddress(query))
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                onResult(null)
            }
        }
    }

    fun searchByNostr(query: String, onResult: (List<PublicKey>) -> Unit) {
        scope.launch {
            try {
                onResult(nostr.profiles.searchByNostr(query))
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                onResult(emptyList())
            }
        }
    }

    fun verifyActivity(pubkey: PublicKey, onResult: (Timestamp?) -> Unit) {
        scope.launch {
            try {
                onResult(nostr.profiles.verifyActivity(pubkey))
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                onResult(null)
            }
        }
    }

    fun verifyContact(pubkey: PublicKey, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                onResult(nostr.profiles.verifyContact(pubkey))
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                onResult(false)
            }
        }
    }

    fun mutualContacts(pubkey: PublicKey, onResult: (Set<PublicKey>) -> Unit) {
        scope.launch {
            try {
                onResult(nostr.profiles.mutualContacts(pubkey))
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                onResult(emptySet())
            }
        }
    }

    fun dismissRelayWarning() {
        _state.update { it.copy(isRelayListEmpty = false) }
    }

    fun refetchMsgRelays() {
        scope.launch {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: return@launch
            val relays = nostr.relays.fetchMsgRelays(currentUser)

            if (relays.isNotEmpty()) dismissRelayWarning()
        }
    }

    fun useDefaultMsgRelayList() {
        scope.launch {
            try {
                val defaultRelays = nostr.relays.getDefaultMsgRelayList()
                nostr.relays.setMsgRelays(defaultRelays)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun loadCurrentUserRelayList() {
        scope.launch {
            try {
                val user = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
                val relayList = nostr.relays.getRelayList(user)
                _state.update { it.copy(userRelayList = relayList) }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    private suspend fun currentUserRelayListInternal(): Map<RelayUrl, RelayMetadata?> {
        try {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            return nostr.relays.getRelayList(currentUser)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            return emptyMap()
        }
    }

    fun addInboxRelay(relay: String) {
        scope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserRelayListInternal().toMutableMap()
                relays[relayUrl] = RelayMetadata.WRITE

                nostr.relays.setRelayList(relays)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun addOutboxRelay(relay: String) {
        scope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserRelayListInternal().toMutableMap()
                relays[relayUrl] = RelayMetadata.READ

                nostr.relays.setRelayList(relays)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun removeRelay(relay: String) {
        scope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserRelayListInternal().toMutableMap()
                relays.remove(relayUrl)

                nostr.relays.setRelayList(relays)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun loadCurrentUserMsgRelayList() {
        scope.launch {
            try {
                val user = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
                val msgRelays = nostr.relays.getMsgRelays(user)
                _state.update { it.copy(userMsgRelayList = msgRelays) }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    private suspend fun userLatestMsgRelayList(): List<RelayUrl> {
        try {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            return nostr.relays.getMsgRelays(currentUser)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            return emptyList()
        }
    }

    fun addMsgRelay(relay: String) {
        scope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = userLatestMsgRelayList().toMutableSet()
                relays.add(relayUrl)

                nostr.relays.setMsgRelays(relays.toList())
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun removeMsgRelay(relay: String) {
        scope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = userLatestMsgRelayList().toMutableSet()
                relays.remove(relayUrl)

                nostr.relays.setMsgRelays(relays.toList())
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }
}
