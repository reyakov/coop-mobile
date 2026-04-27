package su.reya.coop

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
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
import su.reya.coop.screens.ChatScreen
import su.reya.coop.screens.HomeScreen
import su.reya.coop.screens.ImportScreen
import su.reya.coop.screens.NewIdentityScreen
import su.reya.coop.screens.OnboardingScreen

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun App(dbPath: String) {
    val context = LocalContext.current
    val nostr = remember { Nostr() }
    val secretStore = remember { SecretStore(context) }
    val viewModel: NostrViewModel = viewModel { NostrViewModel(nostr, secretStore) }

    LaunchedEffect(Unit) {
        viewModel.initAndConnect(dbPath)
    }

    MaterialExpressiveTheme {
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
            return@MaterialExpressiveTheme
        }

        NavHost(
            navController = navController,
            startDestination = if (hasSecret == true) Screen.Home else Screen.Onboarding
        ) {
            composable<Screen.Onboarding> { backStackEntry ->
                OnboardingScreen(
                    onOpenImport = { navController.navigate(Screen.Import) },
                    onOpenNew = { navController.navigate(Screen.NewIdentity) }
                )
            }
            composable<Screen.Import> { backStackEntry ->
                ImportScreen()
            }
            composable<Screen.NewIdentity> { backStackEntry ->
                val isCreating by viewModel.isCreating.collectAsState()

                NewIdentityScreen(
                    isLoading = isCreating,
                    onSave = { name, bio, uri ->
                        viewModel.createIdentity(name, bio, uri?.toString())
                        navController.navigate(Screen.Home)
                    }
                )
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