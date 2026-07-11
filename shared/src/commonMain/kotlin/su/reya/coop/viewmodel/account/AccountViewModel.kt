package su.reya.coop.viewmodel.account

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Timestamp
import su.reya.coop.AppStorage
import su.reya.coop.Profile
import su.reya.coop.nostr.ExternalSignerHandler
import su.reya.coop.nostr.Nostr
import su.reya.coop.repository.MediaRepository
import su.reya.coop.viewmodel.BaseViewModel

class AccountViewModel(
    nostr: Nostr,
    storage: AppStorage,
    mediaRepository: MediaRepository,
    externalSignerHandler: ExternalSignerHandler? = null,
    defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BaseViewModel() {
    private val relays = AccountRelayDelegate(
        nostr = nostr,
        onError = ::showError,
        scope = viewModelScope,
    )

    private val auth = AccountAuthDelegate(
        nostr = nostr,
        storage = storage,
        mediaRepository = mediaRepository,
        externalSignerHandler = externalSignerHandler,
        defaultDispatcher = defaultDispatcher,
        onError = ::showError,
        onSignerReady = {
            profile.getUserMetadata()
            relays.checkRelayList()
        },
        scope = viewModelScope,
    )

    private val profile = AccountProfileDelegate(
        nostr = nostr,
        mediaRepository = mediaRepository,
        onError = ::showError,
        scope = viewModelScope,
    )

    private val contacts = AccountContactDelegate(
        nostr = nostr,
        onError = ::showError,
        scope = viewModelScope,
    )


    val state = auth.state

    val currentUserProfile: StateFlow<Profile?> = profile.currentUserProfile

    val isUpdatingProfile: StateFlow<Boolean> = profile.isUpdatingProfile

    val contactList: StateFlow<Set<PublicKey>> = contacts.contactList

    val isRelayListEmpty: StateFlow<Boolean> = relays.isRelayListEmpty

    val currentUserRelayList: StateFlow<Map<RelayUrl, RelayMetadata?>> = relays.currentUserRelayList

    val currentUserMsgRelayList: StateFlow<List<RelayUrl>> = relays.currentUserMsgRelayList

    init {
        auth.init()
        contacts.init()
    }

    fun logout(onLogout: () -> Unit = {}) = auth.logout(onLogout)

    fun dismissNotificationBanner() = auth.dismissNotificationBanner()

    fun connectExternalSigner() = auth.connectExternalSigner()

    fun isExternalSignerAvailable(): Boolean = auth.isExternalSignerAvailable()

    fun importIdentity(secret: String, password: String? = null) =
        auth.importIdentity(secret, password)

    fun createIdentity(
        name: String,
        bio: String? = null,
        picture: ByteArray? = null,
        contentType: String? = null,
    ) = auth.createIdentity(name, bio, picture, contentType)

    fun updateProfile(
        name: String? = null,
        bio: String? = null,
        picture: ByteArray? = null,
        contentType: String? = null,
    ) {
        profile.updateProfile(name, bio, picture, contentType)
    }

    fun resetInternalState() {
        contacts.reset()
        relays.reset()
    }

    fun addContact(address: String) = contacts.addContact(address)

    fun removeContact(publicKey: PublicKey) = contacts.removeContact(publicKey)

    fun searchByAddress(query: String, onResult: (PublicKey?) -> Unit) =
        contacts.searchByAddress(query, onResult)

    fun searchByNostr(query: String, onResult: (List<PublicKey>) -> Unit) =
        contacts.searchByNostr(query, onResult)

    fun verifyActivity(pubkey: PublicKey, onResult: (Timestamp?) -> Unit) =
        contacts.verifyActivity(pubkey, onResult)

    fun verifyContact(pubkey: PublicKey, onResult: (Boolean) -> Unit) =
        contacts.verifyContact(pubkey, onResult)

    fun mutualContacts(pubkey: PublicKey, onResult: (Set<PublicKey>) -> Unit) =
        contacts.mutualContacts(pubkey, onResult)

    fun dismissRelayWarning() = relays.dismissRelayWarning()

    fun refetchMsgRelays() = relays.refetchMsgRelays()

    fun useDefaultMsgRelayList() = relays.useDefaultMsgRelayList()

    fun loadCurrentUserRelayList() = relays.loadCurrentUserRelayList()

    fun addInboxRelay(relay: String) = relays.addInboxRelay(relay)

    fun addOutboxRelay(relay: String) = relays.addOutboxRelay(relay)

    fun loadCurrentUserMsgRelayList() = relays.loadCurrentUserMsgRelayList()

    fun addMsgRelay(relay: String) = relays.addMsgRelay(relay)

    fun removeMsgRelay(relay: String) = relays.removeMsgRelay(relay)
}
