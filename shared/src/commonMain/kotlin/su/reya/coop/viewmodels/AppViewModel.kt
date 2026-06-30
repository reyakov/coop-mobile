package su.reya.coop.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import su.reya.coop.blossom.BlossomClient
import su.reya.coop.nostr.Nostr
import su.reya.coop.storage.SecretStorage

class AppViewModel(
    private val nostr: Nostr,
    private val secretStore: SecretStorage
) : ViewModel() {
    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _isNotificationBannerDismissed = MutableStateFlow(false)
    val isNotificationBannerDismissed = _isNotificationBannerDismissed.asStateFlow()

    private val _errorEvents = Channel<String>(Channel.BUFFERED)
    val errorEvents = _errorEvents.receiveAsFlow()

    init {
        checkNotificationBannerDismissedStatus()
    }

    fun setBusy(busy: Boolean) {
        _isBusy.value = busy
    }

    fun showError(message: String) {
        viewModelScope.launch {
            _errorEvents.send(message)
        }
    }

    private fun checkNotificationBannerDismissedStatus() {
        viewModelScope.launch {
            _isNotificationBannerDismissed.value =
                secretStore.get("notification_banner_dismissed") == "true"
        }
    }

    fun dismissNotificationBanner() {
        viewModelScope.launch {
            secretStore.set("notification_banner_dismissed", "true")
            _isNotificationBannerDismissed.value = true
        }
    }

    suspend fun blossomUpload(file: ByteArray, contentType: String? = "image/jpeg"): String? {
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
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            withContext(NonCancellable) {
                nostr.disconnect()
            }
        }
    }
}
