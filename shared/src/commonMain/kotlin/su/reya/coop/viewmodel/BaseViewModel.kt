package su.reya.coop.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

abstract class BaseViewModel : ViewModel() {
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val errorEvents = _errorEvents.asSharedFlow()

    protected fun showError(message: String) {
        _errorEvents.tryEmit(message)
    }
}