package su.reya.coop.screens

import androidx.compose.runtime.Composable
import su.reya.coop.LocalAuthViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.Screen
import su.reya.coop.shared.ProfileEditor

@Composable
fun NewIdentityScreen() {
    val authViewModel = LocalAuthViewModel.current
    val navigator = LocalNavigator.current

    ProfileEditor(
        title = "Create a new identity",
        buttonLabel = "Continue",
        onBack = { navigator.goBack() },
        onConfirm = { name, bio, bytes, type ->
            authViewModel.createIdentity(name, bio, bytes, type)
            navigator.navigate(Screen.Home)
        }
    )
}
