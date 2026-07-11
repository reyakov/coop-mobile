package su.reya.coop.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import su.reya.coop.LocalAccountViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.shared.ProfileEditor

@Composable
fun UpdateProfileScreen() {
    val accountViewModel = LocalAccountViewModel.current
    val navigator = LocalNavigator.current

    val currentUser by accountViewModel.currentUserProfile.collectAsStateWithLifecycle()
    val profile = currentUser?.metadata?.asRecord()

    ProfileEditor(
        title = "Update profile",
        buttonLabel = "Save changes",
        initialName = profile?.displayName ?: profile?.name ?: "",
        initialBio = profile?.about ?: "",
        initialPicture = profile?.picture,
        onBack = { navigator.goBack() },
        onConfirm = { name, bio, bytes, type ->
            accountViewModel.updateProfile(name, bio, bytes, type)
            navigator.goBack()
        }
    )
}
