package su.reya.coop

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.MainScope
import su.reya.coop.nostr.NostrManager
import su.reya.coop.repository.AccountRepository
import su.reya.coop.repository.ChatRepository
import su.reya.coop.repository.MediaRepository
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
            accountRepository.state.value.signerRequired == null
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
