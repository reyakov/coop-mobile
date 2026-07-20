package su.reya.coop.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import su.reya.coop.Settings
import su.reya.coop.repository.SettingsRepository

class SettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {
    val settings: StateFlow<Settings> = repository.settings

    fun update(transform: (Settings) -> Settings) {
        repository.update(transform)
    }
}
