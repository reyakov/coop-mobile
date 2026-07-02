package su.reya.coop

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import su.reya.coop.blossom.BlossomClient
import su.reya.coop.nostr.ExternalSignerHandler
import su.reya.coop.nostr.ExternalSignerProxy
import su.reya.coop.nostr.Nostr
import su.reya.coop.nostr.SignerPermissions
import su.reya.coop.storage.SecretStorage
import kotlin.time.Duration.Companion.seconds

data class AuthState(
    val isBusy: Boolean = false,
    val signerRequired: Boolean? = null,
    val isNotificationBannerDismissed: Boolean = false,
)

class AuthViewModel(
    private val nostr: Nostr,
    private val secretStore: SecretStorage,
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
            val dismissed = secretStore.get(KEY_BANNER_DISMISSED) == "true"
            _state.update { it.copy(isNotificationBannerDismissed = dismissed) }
        }
    }

    private fun login() {
        viewModelScope.launch {
            try {
                val secret = withTimeoutOrNull(3.seconds) {
                    secretStore.get(KEY_USER_SIGNER)
                }

                if (secret == null) {
                    _state.update { it.copy(signerRequired = true) }
                    return@launch
                }

                runCatching {
                    val signer = createSigner(secret)
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
                _state.update { it.copy(isBusy = true) }

                // Reset the nostr signer and prune the database
                nostr.signer.switch(Keys.generate())
                nostr.prune()
            } catch (e: Exception) {
                showError("Logout encountered an error: ${e.message}")
            } finally {
                // Clear credentials from persistent storage
                secretStore.clear(KEY_USER_SIGNER)
                secretStore.clear(KEY_BANNER_DISMISSED)

                // Call cleanup callback (e.g. to reset other ViewModels)
                onLogout()

                _state.update { it.copy(isBusy = false, signerRequired = true) }
            }
        }
    }

    fun dismissNotificationBanner() {
        viewModelScope.launch {
            secretStore.set(KEY_BANNER_DISMISSED, "true")
            _state.update { it.copy(isNotificationBannerDismissed = true) }
        }
    }

    private suspend fun getOrInitAppKeys(): Keys {
        val secret = secretStore.get(KEY_APP_KEYS)

        // If app keys are already stored, use them
        if (secret != null) {
            return Keys.parse(secret)
        }

        // Generate new app keys and save to the secret storage
        val keys = Keys.generate()
        secretStore.set(KEY_APP_KEYS, keys.secretKey().toBech32())

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

                // Format: nip55://packageName/hexPubkey
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
                showError("Please approve the connection.")
            }
            return signer.getPublicKeyAsync()
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            return null
        }
    }

    suspend fun importIdentity(secret: String) {
        _state.update { it.copy(isBusy = true) }
        try {
            val signer = createSigner(secret)
            // Update signer
            nostr.setSigner(signer)
            // Persist the secret in the secret storage
            secretStore.set(KEY_USER_SIGNER, secret)
            // Update local states
            _state.update { it.copy(signerRequired = false, isBusy = false) }
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            _state.update { it.copy(isBusy = false) }
        }
    }

    suspend fun connectExternalSigner() {
        val handler = externalSignerHandler ?: throw IllegalStateException("Signer not available")
        _state.update { it.copy(isBusy = true) }
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

            // Update signer
            nostr.setSigner(signer)
            // Store the signer in the secret storage
            secretStore.set(
                KEY_USER_SIGNER,
                "nip55://${result.packageName}/${result.pubkey.toHex()}"
            )
            // Update local states
            _state.update { it.copy(signerRequired = false, isBusy = false) }
        } catch (e: Exception) {
            _state.update { it.copy(isBusy = false) }
            showError("Notice: ${e.message}")
        }
    }

    fun isExternalSignerAvailable(): Boolean {
        return externalSignerHandler?.isAvailable() == true
    }

    suspend fun createIdentity(
        name: String,
        bio: String?,
        picture: ByteArray?,
        contentType: String? = null
    ) {
        _state.update { it.copy(isBusy = true) }

        val keys = Keys.generate()
        val secret = keys.secretKey().toBech32()

        try {
            val avatarUrl = picture?.let { blossomUpload(it, contentType ?: "image/jpeg") }
            // Create identity
            nostr.profiles.createIdentity(keys = keys, name = name, bio = bio, picture = avatarUrl)
            // Persist the secret in the secret storage
            secretStore.set(KEY_USER_SIGNER, secret)
            // Update local states
            _state.update { it.copy(isBusy = false, signerRequired = false) }
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            _state.update { it.copy(isBusy = false) }
        }
    }

    private suspend fun blossomUpload(
        file: ByteArray,
        contentType: String? = "image/jpeg"
    ): String? {
        try {
            val blossom = BlossomClient(
                url = "https://blossom.band",
                client = HttpClient {
                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                            prettyPrint = true
                            isLenient = true
                        })
                    }
                }
            )

            val descriptor = blossom.upload(
                file = file,
                contentType = contentType,
                signer = nostr.signer.get()
            )

            return descriptor?.url
        } catch (e: Exception) {
            showError("Error: ${e.message}")
            return null
        }
    }
}
