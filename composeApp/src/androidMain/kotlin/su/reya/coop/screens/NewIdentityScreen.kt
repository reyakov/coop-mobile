package su.reya.coop.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.Screen
import su.reya.coop.shared.ProfileEditor

@Composable
fun NewIdentityScreen() {
    val viewModel = LocalNostrViewModel.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle(false)

    ProfileEditor(
        title = "Create a new identity",
        buttonLabel = "Continue",
        isBusy = isBusy,
        onBack = { navigator.goBack() },
        onConfirm = { name, bio, bytes, type ->
            scope.launch {
                viewModel.createIdentity(name, bio, bytes, type)
                navigator.navigate(Screen.Home)
            }
        }
    )
}
