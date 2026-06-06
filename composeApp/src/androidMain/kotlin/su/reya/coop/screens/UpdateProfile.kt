package su.reya.coop.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.shared.ProfileEditor

@Composable
fun UpdateProfileScreen() {
    val viewModel = LocalNostrViewModel.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    val currentUser = viewModel.currentUser() ?: return
    val metadata by viewModel.getMetadata(currentUser).collectAsState(initial = null)
    val isBusy by viewModel.isLoggedIn.collectAsStateWithLifecycle(false)

    val profile = metadata?.asRecord()

    ProfileEditor(
        title = "Update profile",
        buttonLabel = "Save changes",
        initialName = profile?.displayName ?: profile?.name ?: "",
        initialBio = profile?.about ?: "",
        initialPicture = profile?.picture,
        isBusy = isBusy,
        onBack = { navigator.goBack() },
        onConfirm = { name, bio, bytes, type ->
            scope.launch {
                viewModel.updateProfile(name, bio, bytes, type)
                navigator.goBack()
            }
        }
    )
}