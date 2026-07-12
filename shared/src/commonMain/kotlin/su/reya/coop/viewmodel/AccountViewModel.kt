package su.reya.coop.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Timestamp
import su.reya.coop.Profile
import su.reya.coop.repository.AccountRepository
import su.reya.coop.repository.AccountState

class AccountViewModel(
    private val repository: AccountRepository,
) : ViewModel(), ErrorHost by repository {

    val state: StateFlow<AccountState> = repository.state
    val isUpdatingProfile: StateFlow<Boolean> = repository.isUpdatingProfile
    val currentUserProfile: StateFlow<Profile?> = repository.currentUserProfile
    val contactList: StateFlow<Set<PublicKey>> = repository.contactList
    val isRelayListEmpty: StateFlow<Boolean> = repository.isRelayListEmpty
    val currentUserRelayList: StateFlow<Map<RelayUrl, RelayMetadata?>> = repository.currentUserRelayList
    val currentUserMsgRelayList: StateFlow<List<RelayUrl>> = repository.currentUserMsgRelayList

    fun logout(onLogout: () -> Unit = {}) = repository.logout(onLogout)
    fun dismissNotificationBanner() = repository.dismissNotificationBanner()
    fun isExternalSignerAvailable() = repository.isExternalSignerAvailable()
    fun importIdentity(secret: String, password: String? = null) = repository.importIdentity(secret, password)
    fun connectExternalSigner() = repository.connectExternalSigner()
    fun createIdentity(name: String, bio: String?, picture: ByteArray?, contentType: String? = null) =
        repository.createIdentity(name, bio, picture, contentType)

    fun getUserMetadata() = repository.getUserMetadata()
    fun updateProfile(name: String? = null, bio: String? = null, picture: ByteArray? = null, contentType: String? = null) =
        repository.updateProfile(name, bio, picture, contentType)

    fun resetInternalState() = repository.resetInternalState()
    fun addContact(address: String) = repository.addContact(address)
    fun removeContact(publicKey: PublicKey) = repository.removeContact(publicKey)
    fun searchByAddress(query: String, onResult: (PublicKey?) -> Unit) = repository.searchByAddress(query, onResult)
    fun searchByNostr(query: String, onResult: (List<PublicKey>) -> Unit) = repository.searchByNostr(query, onResult)
    fun verifyActivity(pubkey: PublicKey, onResult: (Timestamp?) -> Unit) = repository.verifyActivity(pubkey, onResult)
    fun verifyContact(pubkey: PublicKey, onResult: (Boolean) -> Unit) = repository.verifyContact(pubkey, onResult)
    fun mutualContacts(pubkey: PublicKey, onResult: (Set<PublicKey>) -> Unit) = repository.mutualContacts(pubkey, onResult)
    fun checkRelayList() = repository.checkRelayList()
    fun dismissRelayWarning() = repository.dismissRelayWarning()
    fun refetchMsgRelays() = repository.refetchMsgRelays()
    fun useDefaultMsgRelayList() = repository.useDefaultMsgRelayList()
    fun loadCurrentUserRelayList() = repository.loadCurrentUserRelayList()
    fun addInboxRelay(relay: String) = repository.addInboxRelay(relay)
    fun addOutboxRelay(relay: String) = repository.addOutboxRelay(relay)
    fun removeRelay(relay: String) = repository.removeRelay(relay)
    fun loadCurrentUserMsgRelayList() = repository.loadCurrentUserMsgRelayList()
    fun addMsgRelay(relay: String) = repository.addMsgRelay(relay)
    fun removeMsgRelay(relay: String) = repository.removeMsgRelay(relay)
}
