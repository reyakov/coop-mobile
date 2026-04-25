package su.reya.coop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.flow
import su.reya.coop.coop.storage.SecretStore

@Composable
fun App(dbPath: String) {
    val context = LocalContext.current
    val nostr = remember { Nostr() }
    val secretStore = remember { SecretStore(context) }
    val viewModel: NostrViewModel = viewModel { NostrViewModel(nostr, secretStore) }

    LaunchedEffect(Unit) {
        viewModel.initAndConnect(dbPath)
    }

    MaterialTheme {
        rememberCoroutineScope()
        val navController = rememberNavController()

        // Get user's signer status
        val hasSecretFlow = remember {
            flow {
                emit(secretStore.has("user_signer"))
            }
        }
        val hasSecret by hasSecretFlow.collectAsState(initial = null)

        if (hasSecret == null) {
            // Loading state
            return@MaterialTheme
        }

        NavHost(
            navController = navController,
            startDestination = if (hasSecret == true) Screen.Onboarding else Screen.Home
        ) {
            composable<Screen.Onboarding> { backStackEntry ->
                OnboardingScreen(
                    onOpenImport = { navController.navigate(Screen.Import) },
                    onOpenNew = { navController.navigate(Screen.New) }
                )
            }
            composable<Screen.Import> { backStackEntry ->
                ImportScreen()
            }
            composable<Screen.New> { backStackEntry ->
                NewScreen()
            }
            composable<Screen.Home> { backStackEntry ->
                HomeScreen(
                    onOpenChat = { id -> navController.navigate(Screen.Chat(id)) }
                )
            }
            composable<Screen.Chat> { backStackEntry ->
                val chat: Screen.Chat = backStackEntry.toRoute()
                ChatScreen(id = chat.id)
            }
        }
    }
}