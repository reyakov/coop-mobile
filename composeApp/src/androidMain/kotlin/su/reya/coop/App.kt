package su.reya.coop

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val FIRST_TIME_KEY = booleanPreferencesKey("first_time")

@Composable
fun App() {
    MaterialTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val navController = rememberNavController()

        val isFirstTimeFlow = remember {
            context.dataStore.data.map { preferences ->
                preferences[FIRST_TIME_KEY] ?: true
            }
        }
        val isFirstTime by isFirstTimeFlow.collectAsState(initial = null)

        if (isFirstTime == null) {
            // Loading state
            return@MaterialTheme
        }

        NavHost(
            navController = navController,
            startDestination = if (isFirstTime == true) Screen.Welcome else Screen.Home
        ) {
            composable<Screen.Welcome> { backStackEntry ->
                WelcomeScreen(onContinue = {
                    scope.launch {
                        context.dataStore.edit { settings ->
                            settings[FIRST_TIME_KEY] = false
                        }
                        navController.navigate(Screen.Home) {
                            popUpTo<Screen.Welcome> { inclusive = true }
                        }
                    }
                })
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
        }
    }
}