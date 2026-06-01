package su.reya.coop

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.util.Consumer
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
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

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("No Navigator provided")
}

val LocalScanResult = staticCompositionLocalOf<QrScanResult> {
    error("No QrScanResult provided")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun App(viewModel: NostrViewModel) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    val backStack = rememberNavBackStack(Screen.Home)
    val navigator = remember(backStack) { Navigator(backStack) }
    val qrScanResult = remember { QrScanResult() }

    val signerRequired by viewModel.signerRequired.collectAsState(initial = null)
    val isRelayListEmpty by viewModel.isRelayListEmpty.collectAsState()

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // Check if dark theme enabled
    val darkMode = isSystemInDarkTheme()

    // Enabled the dynamic color scheme
    val colorScheme = when {
        // Enable the dynamic color scheme for Android 12+
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(
                context
            )
        }
        // When dark mode is enabled, use the dark color scheme
        darkMode -> darkColorScheme()
        // Fallback to the light color scheme
        else -> expressiveLightColorScheme()
    }

    BackHandler(enabled = backStack.size > 1) {
        navigator.goBack()
    }

    LaunchedEffect(Unit) {
        viewModel.errorEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(activity) {
        activity?.let {
            fun handleIntent(intent: Intent) {
                val screen = Screen.fromIntent(intent)
                // Prevent pushing the same screen
                if (screen != null && backStack.lastOrNull() != screen) {
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
            LocalNostrViewModel provides viewModel,
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
                        HomeScreen()
                    }
                    entry<Screen.Onboarding> {
                        OnboardingScreen()
                    }
                    entry<Screen.Import> {
                        ImportScreen()
                    }
                    entry<Screen.NewIdentity> {
                        NewIdentityScreen()
                    }
                    entry<Screen.Chat> { key ->
                        ChatScreen(id = key.id)
                    }
                    entry<Screen.NewChat> {
                        NewChatScreen()
                    }
                    entry<Screen.Profile> { key ->
                        ProfileScreen(pubkey = key.pubkey)
                    }
                    entry<Screen.Scan> {
                        ScanScreen()
                    }
                    entry<Screen.MyQr> {
                        MyQrScreen()
                    }
                    entry<Screen.Relay> {
                        RelayScreen()
                    }
                }
            )

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
