package su.reya.coop.repository

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

object ErrorRepository {
    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    fun showError(message: String) {
        _errors.trySend(message)
    }
}