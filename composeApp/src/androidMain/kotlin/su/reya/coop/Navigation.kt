package su.reya.coop

import android.content.Intent
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface Screen : NavKey {
    companion object {
        fun fromIntent(intent: Intent): Screen? {
            val data = intent.data ?: return null
            if (data.scheme != "coop") return null

            return when (data.host) {
                // Matches coop://chat/{id}
                "chat" -> data.pathSegments.firstOrNull()?.toLongOrNull()?.let { Chat(it) }
                // Matches coop://profile/{pubkey}
                "profile" -> data.pathSegments.firstOrNull()?.let { Profile(it) }
                else -> null
            }
        }
    }

    @Serializable
    data object Home : Screen

    @Serializable
    data class Chat(val id: Long) : Screen

    @Serializable
    data class Profile(val pubkey: String) : Screen

    @Serializable
    data object UpdateProfile : Screen

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

    @Serializable
    data object Relay : Screen
}
