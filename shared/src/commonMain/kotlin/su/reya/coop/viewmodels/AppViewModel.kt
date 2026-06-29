package su.reya.coop.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            withContext(NonCancellable) {
                nostr.disconnect()
            }
        }
    }
}
