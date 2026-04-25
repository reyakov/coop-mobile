package su.reya.coop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import su.reya.coop.storage.SecretStorage

class NostrViewModel(
    private val nostr: Nostr,
    private val secretStore: SecretStorage
) : ViewModel() {
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    fun initAndConnect(dbPath: String) {
        // Initialize nostr client
        nostr.init(dbPath)

        viewModelScope.launch {
            try {
                // Connect to bootstrap relays
                nostr.connect()
                _isConnected.value = true
            } catch (e: Exception) {
                _isConnected.value = false
                println(e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Ensure all relays are disconnect
        viewModelScope.launch {
            withContext(NonCancellable) {
                nostr.disconnect()
            }
        }
    }
}