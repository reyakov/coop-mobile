package su.reya.coop.viewmodel.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import su.reya.coop.nostr.Nostr
import kotlin.time.Duration.Companion.seconds

class AccountRelayDelegate(
    private val nostr: Nostr,
    private val onError: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _isRelayListEmpty = MutableStateFlow(false)
    val isRelayListEmpty = _isRelayListEmpty.asStateFlow()

    private val _currentUserRelayList = MutableStateFlow<Map<RelayUrl, RelayMetadata?>>(emptyMap())
    val currentUserRelayList = _currentUserRelayList.asStateFlow()

    private val _currentUserMsgRelayList = MutableStateFlow<List<RelayUrl>>(emptyList())
    val currentUserMsgRelayList = _currentUserMsgRelayList.asStateFlow()

    fun reset() {
        _isRelayListEmpty.value = false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun checkRelayList() {
        scope.launch {
            val currentUser = nostr.signer.publicKeyFlow.filterNotNull().first()
            println("user: ${currentUser.toBech32()}")

            // Small delay to ensure subscription is ready
            delay(6.seconds)

            val relays = nostr.relays.getMsgRelays(currentUser)
            if (relays.isEmpty()) _isRelayListEmpty.value = true
        }
    }

    fun dismissRelayWarning() {
        _isRelayListEmpty.value = false
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
                onError("Error: ${e.message}")
            }
        }
    }

    fun loadCurrentUserRelayList() {
        scope.launch {
            try {
                val currentUser =
                    nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
                _currentUserRelayList.value = nostr.relays.getRelayList(currentUser)
            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }
    }

    private suspend fun currentUserRelayListInternal(): Map<RelayUrl, RelayMetadata?> {
        try {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            return nostr.relays.getRelayList(currentUser)
        } catch (e: Exception) {
            onError("Error: ${e.message}")
            return emptyMap()
        }
    }

    fun addInboxRelay(relay: String) {
        scope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserRelayListInternal().toMutableMap()
                relays[relayUrl] = RelayMetadata.WRITE

                nostr.relays.setRelaylist(relays)
            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }
    }

    fun addOutboxRelay(relay: String) {
        scope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserRelayListInternal().toMutableMap()
                relays[relayUrl] = RelayMetadata.READ

                nostr.relays.setRelaylist(relays)
            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }
    }

    fun removeRelay(relay: String) {
        scope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserRelayListInternal().toMutableMap()
                relays.remove(relayUrl)

                nostr.relays.setRelaylist(relays)
            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }
    }

    fun loadCurrentUserMsgRelayList() {
        scope.launch {
            try {
                val currentUser =
                    nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
                _currentUserMsgRelayList.value = nostr.relays.getMsgRelays(currentUser)
            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }
    }

    private suspend fun currentUserMsgRelayListInternal(): List<RelayUrl> {
        try {
            val currentUser = nostr.signer.getPublicKeyAsync() ?: throw Exception("User not found")
            return nostr.relays.getMsgRelays(currentUser)
        } catch (e: Exception) {
            onError("Error: ${e.message}")
            return emptyList()
        }
    }

    fun addMsgRelay(relay: String) {
        scope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserMsgRelayListInternal().toMutableSet()
                relays.add(relayUrl)

                nostr.relays.setMsgRelays(relays.toList())
            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }
    }

    fun removeMsgRelay(relay: String) {
        scope.launch {
            try {
                val relayUrl = RelayUrl.parse(relay)
                val relays = currentUserMsgRelayListInternal().toMutableSet()
                relays.remove(relayUrl)

                nostr.relays.setMsgRelays(relays.toList())
            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }
    }
}
