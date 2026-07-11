package su.reya.coop.viewmodel.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rust.nostr.sdk.PublicKey
import su.reya.coop.Profile
import su.reya.coop.nostr.Nostr
import su.reya.coop.repository.MediaRepository

class AccountProfileDelegate(
    private val nostr: Nostr,
    private val mediaRepository: MediaRepository,
    private val onError: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _isUpdatingProfile = MutableStateFlow(false)
    val isUpdatingProfile: StateFlow<Boolean> = _isUpdatingProfile.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentUserProfile: StateFlow<Profile?> = nostr.signer.publicKeyFlow
        .flatMapLatest { pubkey ->
            if (pubkey != null) currentUserProfileFlow(pubkey) else flowOf(null)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun currentUserProfileFlow(pubkey: PublicKey) = merge(
        flow {
            nostr.waitUntilInitialized()
            val cached = nostr.profiles.getAllCacheMetadata()[pubkey]
            if (cached != null) emit(Profile(pubkey, cached))
            nostr.profiles.fetchMetadataBatch(listOf(pubkey))
        },
        nostr.profiles.metadataUpdates
            .filter { (p, _) -> p == pubkey }
            .map { (p, m) -> Profile(p, m) }
    )

    fun getUserMetadata() {
        scope.launch {
            nostr.profiles.getUserMetadata()
        }
    }

    fun updateProfile(
        name: String? = null,
        bio: String? = null,
        picture: ByteArray? = null,
        contentType: String? = null
    ) {
        scope.launch {
            _isUpdatingProfile.value = true
            try {
                val avatarUrl = picture?.let {
                    mediaRepository.blossomUpload(
                        nostr.signer.get(),
                        it,
                        contentType ?: "image/jpeg"
                    )
                }
                nostr.profiles.updateProfile(name, bio, avatarUrl)
                _isUpdatingProfile.value = false
            } catch (e: Exception) {
                onError("Error: ${e.message}")
                _isUpdatingProfile.value = false
            }
        }
    }
}
