package su.reya.coop.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.EncryptedSecretKey
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import su.reya.coop.AppStorage
import su.reya.coop.nostr.ExternalSignerHandler
import su.reya.coop.nostr.ExternalSignerProxy
import su.reya.coop.nostr.Nostr
import su.reya.coop.nostr.SignerPermissions
import su.reya.coop.repository.MediaRepository
import kotlin.time.Duration.Companion.seconds

data class AuthState(
    val signerRequired: Boolean? = null,
    val isNotificationBannerDismissed: Boolean = false,
    val isImporting: Boolean = false,
    val importError: String? = null,
)

class AuthViewModel(
    private val nostr: Nostr,
    private val storage: AppStorage,
    private val mediaRepository: MediaRepository,
    private val externalSignerHandler: ExternalSignerHandler? = null,
) : BaseViewModel() {
    companion object {
        private const val KEY_USER_SIGNER = "user_signer"
        private const val KEY_APP_KEYS = "app_keys"
        private const val KEY_BANNER_DISMISSED = "notification_banner_dismissed"
    }

    private val _state = MutableStateFlow(AuthState())
    val state = _state.asStateFlow()

    init {
        // Check if the notification banner has been dismissed
        checkNotificationBannerDismissedStatus()

        // Check local stored secret (secret key or bunker)
        login()
    }

    private fun checkNotificationBannerDismissedStatus() {
        viewModelScope.launch {
            val dismissed = storage.get(KEY_BANNER_DISMISSED) == "true"
            _state.update { it.copy(isNotificationBannerDismissed = dismissed) }
        }
    }

    private fun login() {
        viewModelScope.launch {
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
        viewModelScope.launch {
            try {
                // Reset the nostr signer and prune the database
                nostr.signer.switch(Keys.generate())
                nostr.prune()
            } catch (e: Exception) {
                showError("Logout encountered an error: ${e.message}")
            } finally {
                // Clear credentials from persistent storage
                storage.clear(KEY_USER_SIGNER)
                storage.clear(KEY_BANNER_DISMISSED)
                // Call cleanup callback (e.g. to reset other ViewModels)
                onLogout()
                // Reset local states
                _state.update { it.copy(signerRequired = true) }
            }
        }
    }

    fun dismissNotificationBanner() {
        viewModelScope.launch {
            storage.set(KEY_BANNER_DISMISSED, "true")
            _state.update { it.copy(isNotificationBannerDismissed = true) }
        }
    }

    private suspend fun getOrInitAppKeys(): Keys {
        val secret = storage.getSecret(KEY_APP_KEYS)
        // If app keys are already stored, use them
        if (secret != null) return Keys.parse(secret)
        // Generate new app keys and save to the secret storage
        val keys = Keys.generate()
        storage.setSecret(KEY_APP_KEYS, keys.secretKey().toBech32())
        return keys
    }

    private suspend fun createSigner(
        secret: String,
        password: String? = null
    ): Pair<AsyncNostrSigner, String?> {
        return when {
            secret.startsWith("nsec1") -> Keys.parse(secret) to null

            secret.startsWith("ncryptsec1") -> {
                if (password == null) throw IllegalArgumentException("Password is required")
                val enc = EncryptedSecretKey.fromBech32(secret)
                val secret = enc.decrypt(password)
                val keys = Keys(secret)

                keys to keys.secretKey().toBech32()
            }

            secret.startsWith("bunker://") -> {
                val appKeys = getOrInitAppKeys()
                val bunker = NostrConnectUri.parse(secret)
                val timeout = 50.seconds
                NostrConnect(uri = bunker, appKeys, timeout, null) to null
            }

            secret.startsWith("nip55://") -> {
                val handler = externalSignerHandler
                    ?: throw IllegalStateException("External signer not available on this platform")

                // Format: nip55://packageName/hexPubkey
                val parts = secret.removePrefix("nip55://").split("/", limit = 2)
                val packageName = parts[0]
                val pubkey = PublicKey.parse(parts[1])

                handler.setPackageName(packageName)
                ExternalSignerProxy(handler, pubkey) to null
            }

            else -> throw IllegalArgumentException("Invalid secret format")
        }
    }

    fun importIdentity(secret: String, password: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, importError = null) }
            try {
                val (signer, decryptedSecret) = createSigner(secret, password)
                // Update signer
                nostr.setSigner(signer)
                // Persist the secret in the secret storage
                storage.setSecret(KEY_USER_SIGNER, decryptedSecret ?: secret)
                // Update local states
                _state.update { it.copy(signerRequired = false, isImporting = false) }
            } catch (e: Exception) {
                showError("Import failed: ${e.message}")
                _state.update { it.copy(isImporting = false, importError = e.message) }
            }
        }
    }

    fun connectExternalSigner() {
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, importError = null) }
            try {
                val handler = externalSignerHandler
                    ?: throw IllegalStateException("Signer not available")

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

                // Update signer
                nostr.setSigner(signer)
                // Store the signer in the secret storage
                storage.setSecret(
                    KEY_USER_SIGNER,
                    "nip55://${result.packageName}/${result.pubkey.toHex()}"
                )
                // Update local states
                _state.update { it.copy(signerRequired = false, isImporting = false) }
            } catch (e: Exception) {
                showError("External signer connection failed: ${e.message}")
                _state.update { it.copy(isImporting = false, importError = e.message) }
            }
        }
    }

    fun isExternalSignerAvailable(): Boolean {
        return externalSignerHandler?.isAvailable() == true
    }

    fun createIdentity(
        name: String,
        bio: String?,
        picture: ByteArray?,
        contentType: String? = null
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, importError = null) }
            try {
                val keys = Keys.generate()
                val secret = keys.secretKey().toBech32()
                val avatarUrl = picture?.let {
                    mediaRepository.blossomUpload(keys, it, contentType ?: "image/jpeg")
                }
                // Create identity
                nostr.profiles.createIdentity(
                    keys = keys,
                    name = name,
                    bio = bio,
                    picture = avatarUrl
                )
                // Persist the secret in the secret storage
                storage.setSecret(KEY_USER_SIGNER, secret)
                // Update local states
                _state.update { it.copy(signerRequired = false, isImporting = false) }
            } catch (e: Exception) {
                showError("Identity creation failed: ${e.message}")
                _state.update { it.copy(isImporting = false, importError = e.message) }
            }
        }
    }
}
