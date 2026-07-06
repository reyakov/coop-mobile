package su.reya.coop.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import su.reya.coop.LocalAuthViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.Screen
import su.reya.coop.shared.ProfileEditor

@Composable
fun NewIdentityScreen() {
    val authViewModel = LocalAuthViewModel.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    ProfileEditor(
        title = "Create a new identity",
        buttonLabel = "Continue",
        onBack = { navigator.goBack() },
        onConfirm = { name, bio, bytes, type ->
            scope.launch {
                authViewModel.createIdentity(name, bio, bytes, type)
                navigator.navigate(Screen.Home)
            }
        }
    )
}
