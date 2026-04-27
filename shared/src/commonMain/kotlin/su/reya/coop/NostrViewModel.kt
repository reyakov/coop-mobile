package su.reya.coop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Keys
import su.reya.coop.storage.SecretStorage

class NostrViewModel(
    private val nostr: Nostr,
    private val secretStore: SecretStorage
) : ViewModel() {
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating = _isCreating.asStateFlow()

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

    fun createIdentity(name: String, bio: String, picture: String?) {
        viewModelScope.launch {
            try {
                val keys = Keys.generate()
                val secret = keys.secretKey().toBech32()
                // Set loading state
                _isCreating.value = true
                // Create identity
                nostr.createIdentity(keys, name, bio, picture)
                // Save secret to the secret storage
                secretStore.set("user_signer", secret)
            } catch (e: Exception) {
                _isCreating.value = false
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