package su.reya.coop.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import su.reya.coop.nostr.Nostr
import kotlin.time.Duration.Companion.seconds

class RelayViewModel(
    private val nostr: Nostr,
) : BaseViewModel() {
    private val _isRelayListEmpty = MutableStateFlow(false)
    val isRelayListEmpty: StateFlow<Boolean> = _isRelayListEmpty.asStateFlow()

    private val _currentUserRelayList = MutableStateFlow<Map<RelayUrl, RelayMetadata?>>(emptyMap())
    val currentUserRelayList = _currentUserRelayList.asStateFlow()

    private val _currentUserMsgRelayList = MutableStateFlow<List<RelayUrl>>(emptyList())
    val currentUserMsgRelayList = _currentUserMsgRelayList.asStateFlow()

    init {
        checkRelayList()
    }

    private fun checkRelayList() {
        viewModelScope.launch {
            // Wait until the client is ready
            nostr.waitUntilInitialized()

            // Wait until a signer is explicitly set (which updates publicKeyFlow)
            val currentUser = nostr.signer.publicKeyFlow.filterNotNull().first()

            // Small delay to ensure all relays are connected
            delay(2.seconds)

            // Check if the relay list is empty
            val relays = nostr.relays.getMsgRelays(currentUser)
            if (relays.isEmpty()) _isRelayListEmpty.value = true
        }
    }

    fun dismissRelayWarning() {
        _isRelayListEmpty.value = false
    }

    fun resetInternalState() {
        _isRelayListEmpty.value = false
    }

    fun refetchMsgRelays() {
        viewModelScope.launch {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: return@launch
            val relays = nostr.relays.fetchMsgRelays(currentUser)

            if (relays.isNotEmpty()) dismissRelayWarning()
        }
    }

    fun useDefaultMsgRelayList() {
        viewModelScope.launch {
            try {
                val defaultRelays = nostr.relays.getDefaultMsgRelayList()
                nostr.relays.setMsgRelays(defaultRelays)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun loadCurrentUserRelayList() {
        viewModelScope.launch {
            try {
                val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
                _currentUserRelayList.value = nostr.relays.getRelayList(currentUser)
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
        viewModelScope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserRelayListInternal().toMutableMap()
                relays[relayUrl] = RelayMetadata.WRITE

                nostr.relays.setRelaylist(relays)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun addOutboxRelay(relay: String) {
        viewModelScope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserRelayListInternal().toMutableMap()
                relays[relayUrl] = RelayMetadata.READ

                nostr.relays.setRelaylist(relays)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun removeRelay(relay: String) {
        viewModelScope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserRelayListInternal().toMutableMap()
                relays.remove(relayUrl)

                nostr.relays.setRelaylist(relays)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun loadCurrentUserMsgRelayList() {
        viewModelScope.launch {
            try {
                val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
                _currentUserMsgRelayList.value = nostr.relays.getMsgRelays(currentUser)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    private suspend fun currentUserMsgRelayListInternal(): List<RelayUrl> {
        try {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            return nostr.relays.getMsgRelays(currentUser)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            return emptyList()
        }
    }

    fun addMsgRelay(relay: String) {
        viewModelScope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserMsgRelayListInternal().toMutableSet()
                relays.add(relayUrl)

                nostr.relays.setMsgRelays(relays.toList())
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun removeMsgRelay(relay: String) {
        viewModelScope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserMsgRelayListInternal().toMutableSet()
                relays.remove(relayUrl)

                nostr.relays.setMsgRelays(relays.toList())
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }
}
