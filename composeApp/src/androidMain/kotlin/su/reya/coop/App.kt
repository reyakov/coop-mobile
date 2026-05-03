package su.reya.coop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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

    val darkMode = isSystemInDarkTheme()
    val colorScheme = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            if (darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkMode -> darkColorScheme()
        else -> lightColorScheme()
    }

    LaunchedEffect(Unit) {
        viewModel.initAndConnect(dbPath)
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
    ) {
        rememberCoroutineScope()
        val navController = rememberNavController()
        val hasSecret by viewModel.hasSecret.collectAsState(initial = null)

        LaunchedEffect(hasSecret) {
            // Navigate to the home screen if the secret is already set
            if (hasSecret == true) {
                // Start a background notification handler
                viewModel.startNotificationHandler()

                // Navigate to the home screen
                navController.navigate(Screen.Home) {
                    popUpTo(Screen.Onboarding) { inclusive = true }
                }
            }
        }

        // Show loading screen while initializing
        if (hasSecret == null) return@MaterialExpressiveTheme

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
                val isCreating by viewModel.isCreating.collectAsState()

                ImportScreen(
                    isLoading = isCreating,
                    onSave = { secret ->
                        viewModel.import(secret)
                    }
                )
            }
            composable<Screen.NewIdentity> { backStackEntry ->
                val isCreating by viewModel.isCreating.collectAsState()

                NewIdentityScreen(
                    isLoading = isCreating,
                    onSave = { name, bio, uri ->
                        viewModel.createIdentity(name, bio, uri?.toString())
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