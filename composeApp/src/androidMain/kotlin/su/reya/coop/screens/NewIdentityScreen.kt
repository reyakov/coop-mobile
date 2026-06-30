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
import su.reya.coop.Screen
import su.reya.coop.shared.ProfileEditor

@Composable
fun NewIdentityScreen() {
    val appViewModel = LocalAppViewModel.current
    val accountViewModel = LocalAccountViewModel.current
    val profileViewModel = LocalProfileViewModel.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val isBusy by appViewModel.isBusy.collectAsStateWithLifecycle(false)

    ProfileEditor(
        title = "Create a new identity",
        buttonLabel = "Continue",
        isBusy = isBusy,
        onBack = { navigator.goBack() },
        onConfirm = { name, bio, bytes, type ->
            scope.launch {
                accountViewModel.createIdentity(name, bio, bytes, type)
                navigator.navigate(Screen.Home)
            }
        }
    )
}
