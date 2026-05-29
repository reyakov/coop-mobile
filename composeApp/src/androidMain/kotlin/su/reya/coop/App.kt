package su.reya.coop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import su.reya.coop.screens.ChatScreen
import su.reya.coop.screens.HomeScreen
import su.reya.coop.screens.ImportScreen
import su.reya.coop.screens.MyQrScreen
import su.reya.coop.screens.NewChatScreen
import su.reya.coop.screens.NewIdentityScreen
import su.reya.coop.screens.OnboardingScreen
import su.reya.coop.screens.ProfileScreen
import su.reya.coop.screens.RelayScreen
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun App(viewModel: NostrViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val darkMode = isSystemInDarkTheme()

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

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
        typography = Typography(),
        motionScheme = MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(
            LocalNostrViewModel provides viewModel,
            LocalSnackbarHostState provides snackbarHostState,
            LocalNavController provides navController,
        ) {
            val emptySecret by viewModel.emptySecret.collectAsState(initial = null)
            val isRelayListEmpty by viewModel.isRelayListEmpty.collectAsState()
            val sheetState = rememberModalBottomSheetState()

            LaunchedEffect(emptySecret) {
                // Navigate to the home screen if the secret is already set
                if (emptySecret == false) {
                    navController.navigate(Screen.Home) {
                        popUpTo(Screen.Onboarding) { inclusive = true }
                    }
                }
            }

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
                composable<Screen.Chat>(
                    deepLinks = listOf(
                        navDeepLink<Screen.Chat>(basePath = "coop://chat")
                    )
                ) { backStackEntry ->
                    val chat: Screen.Chat = backStackEntry.toRoute()
                    ChatScreen(
                        id = chat.id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<Screen.Profile> { backStackEntry ->
                    val profile: Screen.Profile = backStackEntry.toRoute()
                    ProfileScreen(
                        pubkey = profile.pubkey,
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
                composable<Screen.MyQr> { backStackEntry ->
                    MyQrScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<Screen.Relay> { backStackEntry ->
                    RelayScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // Show the relay setup dialog if the msg relay list is empty
            if (isRelayListEmpty) {
                ModalBottomSheet(
                    onDismissRequest = { viewModel.dismissRelayWarning() },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.5f)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Messaging Relays are required",
                            style = MaterialTheme.typography.headlineSmallEmphasized.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "Coop cannot found your messaging relays. To send and receive messages on Coop, you need to set up at least one messaging relay.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "Please click the button below to continue with the default set of relays. You can always change them later in the settings.",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontStyle = FontStyle.Italic,
                            ),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                scope.launch {
                                    viewModel.useDefaultMsgRelayList()
                                    sheetState.hide()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ButtonDefaults.MediumContainerHeight),
                        ) {
                            Text(
                                text = "Continue",
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                        }
                    }
                }
            }
        }
    }
}
