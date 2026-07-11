package su.reya.coop.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import su.reya.coop.LocalAccountViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.Screen
import su.reya.coop.shared.ProfileEditor

@Composable
fun NewIdentityScreen() {
    val accountViewModel = LocalAccountViewModel.current
    val navigator = LocalNavigator.current
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()

    ProfileEditor(
        title = "Create a new identity",
        buttonLabel = "Continue",
        isBusy = accountState.isImporting,
        onBack = { navigator.goBack() },
        onConfirm = { name, bio, bytes, type ->
            accountViewModel.createIdentity(name, bio, bytes, type)
            navigator.navigate(Screen.Home)
        }
    )
}
