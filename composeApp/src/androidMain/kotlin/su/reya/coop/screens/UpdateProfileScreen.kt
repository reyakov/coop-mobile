package su.reya.coop.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import su.reya.coop.LocalAccountViewModel
import su.reya.coop.LocalAppViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalProfileViewModel
import su.reya.coop.shared.ProfileEditor

@Composable
fun UpdateProfileScreen() {
    val appViewModel = LocalAppViewModel.current
    val accountViewModel = LocalAccountViewModel.current
    val profileViewModel = LocalProfileViewModel.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    val currentUser = accountViewModel.nostr.signer.currentUser ?: return
    val metadata by profileViewModel.getMetadata(currentUser).collectAsStateWithLifecycle()
    val isBusy by appViewModel.isBusy.collectAsStateWithLifecycle(false)

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
                profileViewModel.updateProfile(name, bio, bytes, type)
                navigator.goBack()
            }
        }
    )
}