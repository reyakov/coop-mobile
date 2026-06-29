package su.reya.coop

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import su.reya.coop.coop.storage.SecretStore
import su.reya.coop.nostr.NostrManager
import su.reya.coop.viewmodels.AccountViewModel
import su.reya.coop.viewmodels.AppViewModel
import su.reya.coop.viewmodels.ChatViewModel
import su.reya.coop.viewmodels.ProfileViewModel
import su.reya.coop.viewmodels.RelayViewModel
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    companion object {
        val externalSignerLauncher = ExternalSignerLauncher()
    }

    private val factory by lazy {
        object : ViewModelProvider.Factory {
            private val secretStore = SecretStore(this@MainActivity)
            private val androidSigner =
                AndroidExternalSigner(this@MainActivity, externalSignerLauncher)
            private val appVM = AppViewModel(NostrManager.instance, secretStore)
            private val profileVM = ProfileViewModel(NostrManager.instance, appVM)
            private val chatVM = ChatViewModel(NostrManager.instance, appVM)
            private val accountVM =
                AccountViewModel(NostrManager.instance, secretStore, appVM, androidSigner)
            private val relayVM = RelayViewModel(NostrManager.instance, appVM, chatVM, profileVM)

            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when {
                    modelClass.isAssignableFrom(AppViewModel::class.java) -> appVM
                    modelClass.isAssignableFrom(AccountViewModel::class.java) -> accountVM
                    modelClass.isAssignableFrom(ChatViewModel::class.java) -> chatVM
                    modelClass.isAssignableFrom(ProfileViewModel::class.java) -> profileVM
                    modelClass.isAssignableFrom(RelayViewModel::class.java) -> relayVM
                    else -> throw IllegalArgumentException("Unknown ViewModel class")
                } as T
            }
        }
    }

    private val appViewModel: AppViewModel by viewModels { factory }
    private val accountViewModel: AccountViewModel by viewModels { factory }
    private val chatViewModel: ChatViewModel by viewModels { factory }
    private val profileViewModel: ProfileViewModel by viewModels { factory }
    private val relayViewModel: RelayViewModel by viewModels { factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            throwable.printStackTrace()

            Log.e(
                "CoopCrash", "Uncaught exception in thread ${thread.name}", throwable
            )

            // Start the Crash Activity
            val intent = Intent(this, CrashActivity::class.java).apply {
                putExtra("error", throwable.stackTraceToString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)

            // Exit
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }

        val resultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            externalSignerLauncher.onResult(result)
        }
        externalSignerLauncher.register(resultLauncher)

        val splashScreen = installSplashScreen()
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, NostrForegroundService::class.java)
        startForegroundService(serviceIntent)

        // Keep the splash screen visible until the signer check is complete
        splashScreen.setKeepOnScreenCondition {
            accountViewModel.signerRequired.value == null
        }

        // Bind the lifecycle of the ViewModels
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appViewModel.viewModelScope.launch {
                    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        coroutineScope {
                            launch { chatViewModel.bindObservers() }
                            launch { profileViewModel.bindObservers() }
                        }
                    }
                }
            }
        })
        relayViewModel.observeSignerAndCheckRelays()

        setContent {
            App(
                appViewModel = appViewModel,
                accountViewModel = accountViewModel,
                chatViewModel = chatViewModel,
                profileViewModel = profileViewModel,
                relayViewModel = relayViewModel,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
