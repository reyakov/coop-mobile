package su.reya.coop.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import su.reya.coop.LocalNavigator
import su.reya.coop.shared.ProfileEditor
import su.reya.coop.viewmodel.AccountViewModel

@Composable
fun UpdateProfileScreen(viewModel: AccountViewModel) {
    val navigator = LocalNavigator.current
    val currentUser by viewModel.currentUserProfile.collectAsStateWithLifecycle()
    val profile = currentUser?.metadata?.asRecord()
    val isUpdatingProfile by viewModel.isUpdatingProfile.collectAsStateWithLifecycle()

    ProfileEditor(
        title = "Update profile",
        buttonLabel = "Save changes",
        initialName = profile?.displayName ?: profile?.name ?: "",
        initialBio = profile?.about ?: "",
        initialPicture = profile?.picture,
        isBusy = isUpdatingProfile,
        onBack = { navigator.goBack() },
        onConfirm = { name, bio, bytes, type ->
            viewModel.updateProfile(name, bio, bytes, type)
            navigator.goBack()
        }
    )
}
