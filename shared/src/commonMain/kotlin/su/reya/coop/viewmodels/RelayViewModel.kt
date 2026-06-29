package su.reya.coop.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import su.reya.coop.nostr.Nostr
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RelayViewModel(
    private val nostr: Nostr,
    private val appViewModel: AppViewModel,
    private val chatViewModel: ChatViewModel,
    private val profileViewModel: ProfileViewModel
) : ViewModel() {
    private val _isRelayListEmpty = MutableStateFlow(false)
    val isRelayListEmpty = _isRelayListEmpty.asStateFlow()

    fun reconnect() {
        viewModelScope.launch {
            nostr.waitUntilInitialized()
            nostr.reconnect()
        }
    }

    fun observeSignerAndCheckRelays() {
        viewModelScope.launch {
            while (true) {
                val pubkey = nostr.signer.currentUser
                if (pubkey != null) {
                    val rooms = nostr.messages.getChatRooms() ?: emptySet()
                    if (rooms.isNotEmpty()) {
                        chatViewModel.mergeChatRooms(rooms)
                        // Note: isPartialProcessedGiftWrap is in ChatViewModel
                        // We might need to expose a way to set it or just let it be updated by observers
                    }

                    profileViewModel.getUserMetadata()

                    delay(2.seconds)

                    val relays = nostr.relays.getMsgRelays(pubkey)
                    if (relays.isEmpty()) _isRelayListEmpty.value = true
                    break
                }
                delay(500.milliseconds)
            }
        }
    }

    suspend fun refetchMsgRelays(pubkey: PublicKey) {
        val relays = nostr.relays.fetchMsgRelays(pubkey)
        if (relays.isNotEmpty()) dismissRelayWarning()
    }

    fun dismissRelayWarning() {
        _isRelayListEmpty.value = false
    }

    suspend fun useDefaultMsgRelayList() {
        try {
            val defaultRelays = nostr.relays.getDefaultMsgRelayList()
            nostr.relays.setMsgRelays(defaultRelays)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        }
    }

    suspend fun currentUserRelayList(): Map<RelayUrl, RelayMetadata?> {
        try {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            return nostr.relays.getRelayList(currentUser)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
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
            appViewModel.showError("Error: ${e.message}")
        }
    }

    suspend fun addOutboxRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserRelayList().toMutableMap()
            relays[relayUrl] = RelayMetadata.READ
            nostr.relays.setRelaylist(relays)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        }
    }

    suspend fun removeRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserRelayList().toMutableMap()
            relays.remove(relayUrl)
            nostr.relays.setRelaylist(relays)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        }
    }

    suspend fun currentUserMsgRelayList(): List<RelayUrl> {
        try {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            return nostr.relays.getMsgRelays(currentUser)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
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
            appViewModel.showError("Error: ${e.message}")
        }
    }

    suspend fun removeMsgRelay(relay: String) {
        try {
            val relayUrl = RelayUrl.parse(relay)
            val relays = currentUserMsgRelayList().toMutableSet()
            relays.remove(relayUrl)
            nostr.relays.setMsgRelays(relays.toList())
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        }
    }
}
