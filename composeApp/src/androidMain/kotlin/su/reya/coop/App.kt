package su.reya.coop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import su.reya.coop.coop.storage.SecretStore
import su.reya.coop.screens.ChatScreen
import su.reya.coop.screens.HomeScreen
import su.reya.coop.screens.ImportScreen
import su.reya.coop.screens.NewChatScreen
import su.reya.coop.screens.NewIdentityScreen
import su.reya.coop.screens.OnboardingScreen
import su.reya.coop.screens.ScanScreen

val LocalNostrViewModel = staticCompositionLocalOf<NostrViewModel> {
    error("No NostrViewModel provided")
}

val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("No SnackbarHostState provided")
}

val LocalNavController = staticCompositionLocalOf<NavController> {
    error("No NavController provided")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun App() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val darkMode = isSystemInDarkTheme()

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // Initialize Nostr View Model and Secret Store
    val secretStore = remember { SecretStore(context) }
    val viewModel: NostrViewModel = viewModel { NostrViewModel(NostrManager.instance, secretStore) }

    // Enabled the dynamic color scheme
    val colorScheme = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            if (darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkMode -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }

    LaunchedEffect(Unit) {
        viewModel.errorEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
    ) {
        CompositionLocalProvider(
            LocalNostrViewModel provides viewModel,
            LocalSnackbarHostState provides snackbarHostState,
            LocalNavController provides navController,
        ) {
            val emptySecret by viewModel.emptySecret.collectAsState(initial = null)

            LaunchedEffect(emptySecret) {
                // Navigate to the home screen if the secret is already set
                if (emptySecret == false) {
                    navController.navigate(Screen.Home) {
                        popUpTo(Screen.Onboarding) { inclusive = true }
                    }
                }
            }

            // Show loading screen while initializing
            if (emptySecret == null) return@CompositionLocalProvider

            NavHost(
                navController = navController,
                startDestination = if (emptySecret == false) Screen.Home else Screen.Onboarding
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
                        onBack = { navController.popBackStack() },
                        onSave = { secret ->
                            viewModel.importIdentity(secret)
                        }
                    )
                }
                composable<Screen.NewIdentity> { backStackEntry ->
                    val isCreating by viewModel.isCreating.collectAsState()

                    NewIdentityScreen(
                        isLoading = isCreating,
                        onBack = { navController.popBackStack() },
                        onSave = { name, bio, uri ->
                            val contentType = uri?.let { context.contentResolver.getType(it) }
                            val picture = uri?.let {
                                context.contentResolver.openInputStream(it)?.use { input ->
                                    input.readBytes()
                                }
                            }
                            viewModel.createIdentity(name, bio, picture, contentType)
                        }
                    )
                }
                composable<Screen.Home> { backStackEntry ->
                    HomeScreen(
                        onOpenChat = { id -> navController.navigate(Screen.Chat(id)) },
                        onNewChat = { navController.navigate(Screen.NewChat) }
                    )
                }
                composable<Screen.Chat> { backStackEntry ->
                    val chat: Screen.Chat = backStackEntry.toRoute()
                    ChatScreen(
                        id = chat.id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<Screen.NewChat> { backStackEntry ->
                    NewChatScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<Screen.Scan> { backStackEntry ->
                    ScanScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}