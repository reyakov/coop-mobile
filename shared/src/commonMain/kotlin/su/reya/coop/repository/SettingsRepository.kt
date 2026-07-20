package su.reya.coop.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import su.reya.coop.AppStorage
import su.reya.coop.Settings

class SettingsRepository(
    private val storage: AppStorage
) {
    companion object {
        private const val KEY_SETTINGS = "app_settings"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun save(settings: Settings) {
        val jsonString = json.encodeToString(settings)
        storage.set(KEY_SETTINGS, jsonString)
    }

    suspend fun load(): Settings {
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
