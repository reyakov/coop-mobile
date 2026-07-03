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
import su.reya.coop.coop.storage.SecretStore
import su.reya.coop.nostr.NostrManager
import su.reya.coop.viewmodel.AuthViewModel
import su.reya.coop.viewmodel.ChatViewModel
import su.reya.coop.viewmodel.NostrViewModel
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    companion object {
        val externalSignerLauncher = ExternalSignerLauncher()
    }

    private val factory by lazy {
        object : ViewModelProvider.Factory {
            private val androidSigner =
                AndroidExternalSigner(this@MainActivity, externalSignerLauncher)
            private val secretStore = SecretStore(this@MainActivity)
            private val nostrViewModel =
                NostrViewModel(NostrManager.instance)
            private val chatViewModel =
                ChatViewModel(NostrManager.instance)
            private val authViewModel =
                AuthViewModel(NostrManager.instance, secretStore, androidSigner)

            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when {
                    modelClass.isAssignableFrom(NostrViewModel::class.java) -> nostrViewModel
                    modelClass.isAssignableFrom(ChatViewModel::class.java) -> chatViewModel
                    modelClass.isAssignableFrom(AuthViewModel::class.java) -> authViewModel
                    else -> throw IllegalArgumentException("Unknown ViewModel class")
                } as T
            }
        }
    }

    private val nostrViewModel: NostrViewModel by viewModels { factory }
    private val chatViewModel: ChatViewModel by viewModels { factory }
    private val authViewModel: AuthViewModel by viewModels { factory }

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
            authViewModel.state.value.signerRequired == null
        }

        setContent {
            App(
                nostrViewModel = nostrViewModel,
                chatViewModel = chatViewModel,
                authViewModel = authViewModel,
            )
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
