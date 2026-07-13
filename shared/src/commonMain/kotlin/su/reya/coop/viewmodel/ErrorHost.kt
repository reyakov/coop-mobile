package su.reya.coop.viewmodel

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

interface ErrorHost {
    val errorEvents: SharedFlow<String>

    fun showError(message: String)
}

fun createErrorHost(): ErrorHost = ErrorHostImpl()

private class ErrorHostImpl : ErrorHost {
    private val _errorEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    override val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    override fun showError(message: String) {
        _errorEvents.tryEmit(message)
    }
}
