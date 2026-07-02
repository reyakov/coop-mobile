package su.reya.coop.viewmodel

import androidx.lifecycle.ViewModel
import su.reya.coop.repository.ErrorRepository

abstract class BaseViewModel : ViewModel() {
    protected fun showError(message: String) {
        ErrorRepository.showError(message)
    }
}