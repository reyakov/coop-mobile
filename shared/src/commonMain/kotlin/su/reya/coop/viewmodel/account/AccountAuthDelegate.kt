package su.reya.coop.viewmodel.account

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

data class AccountState(
    val signerRequired: Boolean? = null,
    val isNotificationBannerDismissed: Boolean = false,
    val isImporting: Boolean = false,
    val importError: String? = null,
)

class AccountAuthDelegate(
    private val nostr: Nostr,
    private val storage: AppStorage,
    private val mediaRepository: MediaRepository,
    private val externalSignerHandler: ExternalSignerHandler? = null,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onError: (String) -> Unit,
    private val onSignerReady: () -> Unit,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val KEY_USER_SIGNER = "user_signer"
        private const val KEY_APP_KEYS = "app_keys"
        private const val KEY_BANNER_DISMISSED = "notification_banner_dismissed"
    }

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    fun init() {
        checkNotificationBannerDismissedStatus()
        login()
    }

    private fun checkNotificationBannerDismissedStatus() {
        scope.launch {
            val dismissed = storage.get(KEY_BANNER_DISMISSED) == "true"
            _state.update { it.copy(isNotificationBannerDismissed = dismissed) }
        }
    }

    private fun login() {
        scope.launch {
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
                    onSignerReady()
                }.onFailure { e ->
                    onError("Login failed: ${e.message}")
                    _state.update { it.copy(signerRequired = true) }
                }
            } catch (e: Exception) {
                onError("Login failed: ${e.message}")
                _state.update { it.copy(signerRequired = true) }
            }
        }
    }

    fun logout(onLogout: () -> Unit = {}) {
        scope.launch {
            try {
                nostr.signer.switch(Keys.generate())
                nostr.prune()
            } catch (e: Exception) {
                onError("Logout encountered an error: ${e.message}")
            } finally {
                storage.clear(KEY_USER_SIGNER)
                storage.clear(KEY_BANNER_DISMISSED)
                onLogout()
                _state.update { it.copy(signerRequired = true) }
            }
        }
    }

    fun dismissNotificationBanner() {
        scope.launch {
            storage.set(KEY_BANNER_DISMISSED, "true")
            _state.update { it.copy(isNotificationBannerDismissed = true) }
        }
    }

    private suspend fun getOrInitAppKeys(): Keys = withContext(defaultDispatcher) {
        val secret = storage.getSecret(KEY_APP_KEYS)
        if (secret != null) return@withContext Keys.parse(secret)
        val keys = Keys.generate()
        storage.setSecret(KEY_APP_KEYS, keys.secretKey().toBech32())
        keys
    }

    private suspend fun createSigner(
        secret: String,
        password: String? = null
    ): Pair<AsyncNostrSigner, String?> = withContext(defaultDispatcher) {
        when {
            secret.startsWith("nsec1") -> Keys.parse(secret) to null

            secret.startsWith("ncryptsec1") -> {
                if (password == null) throw IllegalArgumentException("Password is required")
                val enc = EncryptedSecretKey.fromBech32(secret)
                val decrypted = enc.decrypt(password)
                val keys = Keys(decrypted)
                keys to keys.secretKey().toBech32()
            }

            secret.startsWith("bunker://") -> {
                val appKeys = getOrInitAppKeys()
                val bunker = NostrConnectUri.parse(secret)
                val timeout = 50.seconds
                NostrConnect(uri = bunker, appKeys, timeout, null) to null
            }

            secret.startsWith("nip55://") -> {
                val handler = externalSignerHandler ?: throw IllegalStateException("Not available")
                val parts = secret.removePrefix("nip55://").split("/", limit = 2)
                val packageName = parts[0]
                val pubkey = PublicKey.parse(parts[1])

                handler.setPackageName(packageName)
                ExternalSignerProxy(handler, pubkey) to null
            }

            else -> throw IllegalArgumentException("Invalid secret format")
        }
    }

    fun isExternalSignerAvailable(): Boolean {
        return externalSignerHandler?.isAvailable() == true
    }

    fun importIdentity(secret: String, password: String? = null) {
        scope.launch {
            _state.update { it.copy(isImporting = true, importError = null) }
            try {
                val (signer, decryptedSecret) = createSigner(secret, password)

                nostr.setSigner(signer)
                onSignerReady()

                storage.setSecret(KEY_USER_SIGNER, decryptedSecret ?: secret)
                _state.update { it.copy(signerRequired = false, isImporting = false) }
            } catch (e: Exception) {
                onError("Import failed: ${e.message}")
                _state.update { it.copy(isImporting = false, importError = e.message) }
            }
        }
    }

    fun connectExternalSigner() {
        scope.launch {
            _state.update { it.copy(isImporting = true, importError = null) }
            try {
                val handler =
                    externalSignerHandler ?: throw IllegalStateException("Signer not available")

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
                val uri = "nip55://${result.packageName}/${result.pubkey.toHex()}"

                nostr.setSigner(signer)
                onSignerReady()

                storage.setSecret(KEY_USER_SIGNER, uri)
                _state.update { it.copy(signerRequired = false, isImporting = false) }
            } catch (e: Exception) {
                onError("External signer connection failed: ${e.message}")
                _state.update { it.copy(isImporting = false, importError = e.message) }
            }
        }
    }

    fun createIdentity(
        name: String,
        bio: String?,
        picture: ByteArray?,
        contentType: String? = null
    ) {
        scope.launch {
            _state.update { it.copy(isImporting = true, importError = null) }
            try {
                val keys = Keys.generate()
                val secret = keys.secretKey().toBech32()
                val avatarUrl = picture?.let {
                    mediaRepository.blossomUpload(keys, it, contentType ?: "image/jpeg")
                }
                
                nostr.profiles.createIdentity(
                    keys = keys,
                    name = name,
                    bio = bio,
                    picture = avatarUrl
                )
                onSignerReady()

                storage.setSecret(KEY_USER_SIGNER, secret)
                _state.update { it.copy(signerRequired = false, isImporting = false) }
            } catch (e: Exception) {
                onError("Identity creation failed: ${e.message}")
                _state.update { it.copy(isImporting = false, importError = e.message) }
            }
        }
    }
}
