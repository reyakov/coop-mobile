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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import su.reya.coop.nostr.NostrManager
import su.reya.coop.repository.MediaRepository
import su.reya.coop.viewmodel.ChatViewModel
import su.reya.coop.viewmodel.NostrViewModel
import su.reya.coop.viewmodel.account.AccountViewModel
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    companion object {
        val externalSignerLauncher = ExternalSignerLauncher()
    }

    private val factory by lazy {
        object : ViewModelProvider.Factory {
            private val storage = AppStore(this@MainActivity)
            private val mediaRepository = MediaRepository()
            private val nostrViewModel = NostrViewModel(NostrManager.instance)
            private val chatViewModel = ChatViewModel(NostrManager.instance, mediaRepository)
            private val androidSigner =
                AndroidExternalSigner(this@MainActivity, externalSignerLauncher)
            private val accountViewModel =
                AccountViewModel(NostrManager.instance, storage, mediaRepository, androidSigner)

            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when {
                    modelClass.isAssignableFrom(NostrViewModel::class.java) -> nostrViewModel
                    modelClass.isAssignableFrom(ChatViewModel::class.java) -> chatViewModel
                    modelClass.isAssignableFrom(AccountViewModel::class.java) -> accountViewModel
                    else -> throw IllegalArgumentException("Unknown ViewModel class")
                } as T
            }
        }
    }

    private val nostrViewModel: NostrViewModel by viewModels { factory }
    private val chatViewModel: ChatViewModel by viewModels { factory }
    private val accountViewModel: AccountViewModel by viewModels { factory }

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
            accountViewModel.state.value.signerRequired == null
        }

        setContent {
            App(
                nostrViewModel = nostrViewModel,
                chatViewModel = chatViewModel,
                accountViewModel = accountViewModel,
            )
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
