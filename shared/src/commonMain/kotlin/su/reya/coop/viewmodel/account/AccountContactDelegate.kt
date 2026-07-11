package su.reya.coop.viewmodel.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import su.reya.coop.nostr.Nostr

class AccountContactDelegate(
    private val nostr: Nostr,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private val _contactList = MutableStateFlow<Set<PublicKey>>(emptySet())
    val contactList: StateFlow<Set<PublicKey>> = _contactList.asStateFlow()

    fun init() {
        observeContactList()
    }

    fun reset() {
        _contactList.value = emptySet()
    }

    private fun observeContactList() {
        scope.launch {
            nostr.waitUntilInitialized()
            nostr.profiles.contactListUpdates.collect { contacts ->
                _contactList.value = contacts.toSet()
            }
        }
    }

    private fun newContact(publicKey: PublicKey) {
        if (publicKey in contactList.value) return

        scope.launch {
            try {
                val updated = contactList.value + publicKey
                nostr.profiles.setContactList(updated.toList())
                _contactList.update { it + publicKey }
            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }
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
                onError("Invalid contact address: ${e.message}")
                return@launch
            }

            newContact(pubkey)
        }
    }

    fun removeContact(publicKey: PublicKey) {
        scope.launch {
            if (publicKey !in contactList.value) return@launch

            try {
                val updated = contactList.value - publicKey
                nostr.profiles.setContactList(updated.toList())
                _contactList.update { it - publicKey }
            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }
    }

    fun searchByAddress(query: String, onResult: (PublicKey?) -> Unit) {
        scope.launch {
            try {
                onResult(nostr.profiles.searchByAddress(query))
            } catch (e: Exception) {
                onError("Error: ${e.message}")
                onResult(null)
            }
        }
    }

    fun searchByNostr(query: String, onResult: (List<PublicKey>) -> Unit) {
        scope.launch {
            try {
                onResult(nostr.profiles.searchByNostr(query))
            } catch (e: Exception) {
                onError("Error: ${e.message}")
                onResult(emptyList())
            }
        }
    }

    fun verifyActivity(pubkey: PublicKey, onResult: (Timestamp?) -> Unit) {
        scope.launch {
            try {
                onResult(nostr.profiles.verifyActivity(pubkey))
            } catch (e: Exception) {
                onError("Error: ${e.message}")
                onResult(null)
            }
        }
    }

    fun verifyContact(pubkey: PublicKey, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                onResult(nostr.profiles.verifyContact(pubkey))
            } catch (e: Exception) {
                onError("Error: ${e.message}")
                onResult(false)
            }
        }
    }

    fun mutualContacts(pubkey: PublicKey, onResult: (Set<PublicKey>) -> Unit) {
        scope.launch {
            try {
                onResult(nostr.profiles.mutualContacts(pubkey))
            } catch (e: Exception) {
                onError("Error: ${e.message}")
                onResult(emptySet())
            }
        }
    }
}
