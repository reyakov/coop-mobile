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
import kotlinx.coroutines.MainScope
import su.reya.coop.nostr.NostrManager
import su.reya.coop.repository.AccountRepository
import su.reya.coop.repository.ChatRepository
import su.reya.coop.repository.MediaRepository
import su.reya.coop.viewmodel.AccountViewModel
import su.reya.coop.viewmodel.ChatViewModel
import su.reya.coop.viewmodel.ProfileCache
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    companion object {
        val externalSignerLauncher = ExternalSignerLauncher()
    }

    private val profileCache by lazy { ProfileCache(NostrManager.instance) }
    private val scope = MainScope()

    private val accountRepository by lazy {
        val storage = AppStore(this@MainActivity)
        val mediaRepository = MediaRepository()
        val androidSigner = AndroidExternalSigner(this@MainActivity, externalSignerLauncher)
        AccountRepository(NostrManager.instance, storage, mediaRepository, scope, androidSigner)
    }

    private val chatRepository by lazy {
        val mediaRepository = MediaRepository()
        ChatRepository(NostrManager.instance, mediaRepository, scope)
    }

    private val factory by lazy {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val result = when {
                    modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(
                        chatRepository
                    )

                    modelClass.isAssignableFrom(AccountViewModel::class.java) -> AccountViewModel(
                        accountRepository
                    )

                    else -> throw IllegalArgumentException("Unknown ViewModel class")
                }
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
        }
    }

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
                profileCache = profileCache,
                accountRepository = accountRepository,
                chatRepository = chatRepository,
            )
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
