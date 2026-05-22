package su.reya.coop

import kotlinx.serialization.Serializable

sealed interface Screen {
    @Serializable
    data object Home : Screen

    @Serializable
    data class Chat(val id: Long) : Screen

    @Serializable
    data object NewChat : Screen

    @Serializable
    data object Onboarding : Screen

    @Serializable
    data object Import : Screen

    @Serializable
    data object NewIdentity : Screen

    @Serializable
    data object Scan : Screen

    @Serializable
    data object MyQr : Screen
}
