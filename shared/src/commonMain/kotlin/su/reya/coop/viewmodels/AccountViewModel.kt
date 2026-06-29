package su.reya.coop.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import su.reya.coop.nostr.ExternalSignerHandler
import su.reya.coop.nostr.ExternalSignerProxy
import su.reya.coop.nostr.Nostr
import su.reya.coop.nostr.SignerPermissions
import su.reya.coop.storage.SecretStorage
import kotlin.time.Duration.Companion.seconds

class AccountViewModel(
    val nostr: Nostr,
    private val secretStore: SecretStorage,
    private val appViewModel: AppViewModel,
    private val externalSignerHandler: ExternalSignerHandler? = null,
) : ViewModel() {
    private val _signerRequired = MutableStateFlow<Boolean?>(null)
    val signerRequired = _signerRequired.asStateFlow()

    init {
        // Skip the splash screen if a user is already logged in
        if (nostr.signer.currentUser != null) {
            _signerRequired.value = false
        }
        // Check local stored secret
        login()
    }

    private fun login() {
        viewModelScope.launch {
            try {
                val secret = withTimeoutOrNull(3.seconds) {
                    secretStore.get("user_signer")
                }

                if (secret == null) {
                    _signerRequired.value = true
                    return@launch
                }

                runCatching {
                    val signer = createSigner(secret)
                    nostr.setSigner(signer)
                }.onSuccess {
                    _signerRequired.value = false
                }.onFailure { e ->
                    appViewModel.showError("Login failed: ${e.message}")
                    _signerRequired.value = true
                }
            } catch (e: Exception) {
                appViewModel.showError("Login failed: ${e.message}")
                _signerRequired.value = true
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                appViewModel.setBusy(true)
                // Reset the nostr signer and prune the database
                nostr.signer.switch(Keys.generate())
                nostr.prune()
            } catch (e: Exception) {
                appViewModel.showError("Logout encountered an error: ${e.message}")
            } finally {
                // Clear credentials from persistent storage
                secretStore.clear("user_signer")
                secretStore.clear("notification_banner_dismissed")
                // Reset local states
                appViewModel.setBusy(false)
                _signerRequired.value = true
            }
        }
    }

    private suspend fun getOrInitAppKeys(): Keys {
        val secret = secretStore.get("app_keys")
        if (secret != null) return Keys.parse(secret)

        val keys = Keys.generate()
        secretStore.set("app_keys", keys.secretKey().toBech32())

        return keys
    }

    private suspend fun createSigner(secret: String): AsyncNostrSigner {
        return when {
            secret.startsWith("nsec1") -> Keys.parse(secret)
            secret.startsWith("bunker://") -> {
                val appKeys = getOrInitAppKeys()
                val bunker = NostrConnectUri.parse(secret)
                val timeout = 50.seconds
                NostrConnect(uri = bunker, appKeys, timeout, null)
            }

            secret.startsWith("nip55://") -> {
                val handler = externalSignerHandler
                    ?: throw IllegalStateException("External signer not available on this platform")
                val parts = secret.removePrefix("nip55://").split("/", limit = 2)
                val packageName = parts[0]
                val pubkey = PublicKey.parse(parts[1])
                handler.setPackageName(packageName)
                ExternalSignerProxy(handler, pubkey)
            }

            else -> throw IllegalArgumentException("Invalid secret format")
        }
    }

    suspend fun verifyIdentity(secret: String): PublicKey? {
        try {
            val signer = createSigner(secret)
            if (secret.startsWith("bunker://")) {
                appViewModel.showError("Please approve the connection.")
            }
            return signer.getPublicKeyAsync()
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
            return null
        }
    }

    suspend fun importIdentity(secret: String) {
        appViewModel.setBusy(true)
        try {
            val signer = createSigner(secret)
            nostr.setSigner(signer)
            secretStore.set("user_signer", secret)
            _signerRequired.value = false
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        } finally {
            appViewModel.setBusy(false)
        }
    }

    suspend fun createIdentity(
        name: String,
        bio: String?,
        picture: ByteArray?,
        contentType: String? = "image/jpeg",
        profileViewModel: ProfileViewModel
    ) {
        appViewModel.setBusy(true)
        
        val keys = Keys.generate()
        val secret = keys.secretKey().toBech32()

        try {
            val avatarUrl = picture?.let { profileViewModel.blossomUpload(it, contentType) }
            nostr.profiles.createIdentity(keys = keys, name = name, bio, picture = avatarUrl)
            // Set credentials in persistent storage
            secretStore.set("user_signer", secret)
            // Update local state
            _signerRequired.value = false
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        } finally {
            appViewModel.setBusy(false)
        }
    }

    suspend fun connectExternalSigner() {
        val handler = externalSignerHandler ?: throw IllegalStateException("Signer not available")
        appViewModel.setBusy(true)
        try {
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
            nostr.setSigner(signer)
            // Set credentials in persistent storage
            secretStore.set("user_signer", "nip55://${result.packageName}/${result.pubkey.toHex()}")
            // Update local state
            _signerRequired.value = false
        } catch (e: Exception) {
            throw Exception("Notice: ${e.message}")
        } finally {
            appViewModel.setBusy(false)
        }
    }

    fun isExternalSignerAvailable(): Boolean {
        return externalSignerHandler?.isAvailable() == true
    }
}
