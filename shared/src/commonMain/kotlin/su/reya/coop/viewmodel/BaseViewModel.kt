package su.reya.coop.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

abstract class BaseViewModel : ViewModel() {
    private val _errorEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val errorEvents = _errorEvents.asSharedFlow()

    protected fun showError(message: String) {
        _errorEvents.tryEmit(message)
    }
}