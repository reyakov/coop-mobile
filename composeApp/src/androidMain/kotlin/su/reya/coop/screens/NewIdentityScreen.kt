package su.reya.coop.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val isBusy = authState.isBusy

    ProfileEditor(
        title = "Create a new identity",
        buttonLabel = "Continue",
        isBusy = isBusy,
        onBack = { navigator.goBack() },
        onConfirm = { name, bio, bytes, type ->
            scope.launch {
                authViewModel.createIdentity(name, bio, bytes, type)
                navigator.navigate(Screen.Home)
            }
        }
    )
}
