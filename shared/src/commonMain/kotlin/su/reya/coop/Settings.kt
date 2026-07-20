package su.reya.coop

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val theme: Theme = Theme.System,
    val dynamicColor: Boolean = true,
    val media: MediaConfig = MediaConfig.AlwaysEnabled,
    val screening: Boolean = true,
    val blossomServer: String? = "https://blossom.band",
)

@Serializable
enum class Theme {
    Light, Dark, System
}

@Serializable
enum class MediaConfig {
    Disabled, DisabledForMobileData, AlwaysEnabled
}
