package su.reya.coop

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import su.reya.coop.repository.AccountRepository
import su.reya.coop.repository.ChatRepository
import su.reya.coop.repository.SettingsRepository
import su.reya.coop.screens.ContactListScreen
import su.reya.coop.screens.HomeScreen
import su.reya.coop.screens.ImportScreen
import su.reya.coop.screens.MyQrScreen
import su.reya.coop.screens.NewChatScreen
import su.reya.coop.screens.NewIdentityScreen
import su.reya.coop.screens.OnboardingScreen
import su.reya.coop.screens.ProfileScreen
import su.reya.coop.screens.RelayScreen
import su.reya.coop.screens.RequestListScreen
import su.reya.coop.screens.ScanScreen
import su.reya.coop.screens.UpdateProfileScreen
import su.reya.coop.screens.chat.ChatScreen
import su.reya.coop.viewmodel.AccountViewModel
import su.reya.coop.viewmodel.ChatScreenViewModel
import su.reya.coop.viewmodel.ChatViewModel
import su.reya.coop.viewmodel.ProfileCache
import su.reya.coop.viewmodel.SettingsViewModel

val LocalProfileCache = staticCompositionLocalOf<ProfileCache> {
    error("No ProfileCache provided")
}

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No Settings provided")
}

val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("No SnackbarHostState provided")
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("No Navigator provided")
}

val LocalScanResult = staticCompositionLocalOf<QrScanResult> {
    error("No QrScanResult provided")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun App(
    profileCache: ProfileCache,
    accountRepository: AccountRepository,
    chatRepository: ChatRepository,
    settingsRepository: SettingsRepository,
) {
    val viewModelFactory = remember {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val result = when {
                    modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(
                        chatRepository
                    )

                    modelClass.isAssignableFrom(AccountViewModel::class.java) -> AccountViewModel(
                        accountRepository
                    )

                    modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
                        settingsRepository
                    )

                    else -> throw IllegalArgumentException("Unknown ViewModel class")
                }
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
        }
    }

    val accountViewModel: AccountViewModel = viewModel(factory = viewModelFactory)
    val chatViewModel: ChatViewModel = viewModel(factory = viewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)

    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val backStack = rememberNavBackStack(Screen.Home)
    val navigator = remember(backStack) { Navigator(backStack) }
    val qrScanResult = remember { QrScanResult() }

    // Get the signer required state
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val signerRequired = accountState.signerRequired

    // Get the settings
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // Check if dark theme enabled
    val darkMode = when (settings.theme) {
        Theme.Light -> false
        Theme.Dark -> true
        Theme.System -> isSystemInDarkTheme()
    }

    // Enabled the dynamic color scheme
    val colorScheme = when {
        // Enable the dynamic color scheme for Android 12+
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // When dark mode is enabled, use the dark color scheme
        darkMode -> darkColorScheme()
        // Fallback to the light color scheme
        else -> expressiveLightColorScheme()
    }

    LaunchedEffect(Unit) {
        launch {
            accountViewModel.errorEvents.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }
        launch {
            chatViewModel.errorEvents.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }
        launch {
            profileCache.errorEvents.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    LaunchedEffect(activity) {
        activity?.let {
            fun handleIntent(intent: Intent) {
                val screen = Screen.fromIntent(intent)
                // Prevent pushing the same screen
                if ((screen != null) && (backStack.lastOrNull() != screen)) {
                    navigator.navigate(screen)
                }
            }

            // Handle the intent that started the Activity
            handleIntent(it.intent)

            // Handle new intents while the Activity is running
            val listener = Consumer<Intent> { intent -> handleIntent(intent) }
            it.addOnNewIntentListener(listener)
        }
    }

    LaunchedEffect(backStack.size) {
        if (backStack.isEmpty()) {
            (context as? Activity)?.finish()
        }
    }

    LaunchedEffect(signerRequired) {
        if (signerRequired == true && backStack.last() != Screen.Onboarding) {
            backStack.clear()
            backStack.add(Screen.Onboarding)
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        motionScheme = MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(
            LocalProfileCache provides profileCache,
            LocalSettings provides settings,
            LocalSnackbarHostState provides snackbarHostState,
            LocalNavigator provides navigator,
            LocalScanResult provides qrScanResult,
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = {
                    if (backStack.size > 1) {
                        backStack.removeLastOrNull()
                    } else {
                        (context as? Activity)?.finish()
                    }
                },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                entryProvider = entryProvider {
                    entry<Screen.Home> {
                        HomeScreen(accountViewModel, chatViewModel)
                    }
                    entry<Screen.RequestList> {
                        RequestListScreen(chatViewModel)
                    }
                    entry<Screen.Onboarding> {
                        OnboardingScreen(accountViewModel)
                    }
                    entry<Screen.Import> {
                        ImportScreen(accountViewModel)
                    }
                    entry<Screen.NewIdentity> {
                        NewIdentityScreen(accountViewModel)
                    }
                    entry<Screen.Chat> { key ->
                        val factory = remember(key) {
                            object : ViewModelProvider.Factory {
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    @Suppress("UNCHECKED_CAST")
                                    return ChatScreenViewModel(
                                        key.id,
                                        key.screening,
                                        accountRepository,
                                        chatRepository
                                    ) as T
                                }
                            }
                        }
                        ChatScreen(
                            viewModel<ChatScreenViewModel>(
                                key = key.id.toString(),
                                factory = factory
                            ),
                            accountViewModel
                        )
                    }
                    entry<Screen.NewChat> {
                        NewChatScreen(accountViewModel, chatViewModel)
                    }
                    entry<Screen.Profile> { key ->
                        ProfileScreen(pubkey = key.pubkey, chatViewModel = chatViewModel)
                    }
                    entry<Screen.UpdateProfile> {
                        UpdateProfileScreen(accountViewModel)
                    }
                    entry<Screen.Scan> {
                        ScanScreen()
                    }
                    entry<Screen.MyQr> {
                        MyQrScreen(accountViewModel)
                    }
                    entry<Screen.ContactList> {
                        ContactListScreen(accountViewModel, chatViewModel)
                    }
                    entry<Screen.Relay> {
                        RelayScreen(accountViewModel)
                    }
                }
            )
        }
    }
}

class Navigator(private val backStack: NavBackStack<NavKey>) {
    fun navigate(route: NavKey) {
        backStack.add(route)
    }

    fun goBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
}

class QrScanResult {
    var content by mutableStateOf<String?>(null)

    fun clear() {
        content = null
    }
}
