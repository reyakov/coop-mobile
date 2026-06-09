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
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import su.reya.coop.coop.storage.SecretStore
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    companion object {
        val externalSignerLauncher = ExternalSignerLauncher()
    }

    private val viewModel: NostrViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val secretStore = SecretStore(this@MainActivity)
                val androidSigner = AndroidExternalSigner(this@MainActivity, externalSignerLauncher)
                return NostrViewModel(NostrManager.instance, secretStore, androidSigner) as T
            }
        }
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
            viewModel.signerRequired.value == null
        }

        // Bind the lifecycle of the ViewModel to the Activity's lifecycle'
        viewModel.bindLifecycle(ProcessLifecycleOwner.get().lifecycle)

        setContent {
            App(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
