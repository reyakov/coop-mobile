package su.reya.coop.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import su.reya.coop.AppStorage
import su.reya.coop.Settings

class SettingsRepository(
    private val storage: AppStorage,
    private val scope: CoroutineScope
) {
    companion object {
        private const val KEY_SETTINGS = "app_settings"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    init {
        scope.launch {
            _settings.value = load()
        }
    }

    fun update(transform: (Settings) -> Settings) {
        scope.launch {
            val newSettings = transform(_settings.value)
            _settings.value = newSettings
            save(newSettings)
        }
    }

    private suspend fun save(settings: Settings) {
        val jsonString = json.encodeToString(settings)
        storage.set(KEY_SETTINGS, jsonString)
    }

    private suspend fun load(): Settings {
        val jsonString = storage.get(KEY_SETTINGS)
        return if (jsonString != null) {
            try {
                json.decodeFromString<Settings>(jsonString)
            } catch (_: Exception) {
                Settings()
            }
        } else {
            Settings()
        }
    }
}
