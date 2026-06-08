package su.reya.coop

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import su.reya.coop.coop.storage.SecretStore
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    private lateinit var externalSignerLauncher: AndroidExternalSignerLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        setupCrashHandler()
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        // Initialize the nostr service and external signer
        setupExternalSigner()
        startNostrService()

        // Initialize the ViewModel
        val viewModel: NostrViewModel by viewModels { NostrViewModelFactory(this) }

        // Keep the splash screen visible until the signer check is complete
        splashScreen.setKeepOnScreenCondition {
            viewModel.signerRequired.value == null
        }

        // Bind the lifecycle of the ViewModel to the Activity's lifecycle
        viewModel.bindLifecycle(ProcessLifecycleOwner.get().lifecycle)

        setContent {
            App(viewModel = viewModel)
        }
    }

    private fun setupExternalSigner() {
        val launcher = AndroidExternalSignerLauncher(this)
        ExternalSignerLauncherProvider.launcher = launcher
    }

    private fun startNostrService() {
        val intent = Intent(this, NostrForegroundService::class.java)
        startForegroundService(intent)
    }

    private fun setupCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            throwable.printStackTrace()

            Log.e("CoopCrash", "Uncaught exception in thread ${thread.name}", throwable)

            val intent = Intent(this, CrashActivity::class.java).apply {
                putExtra("error", throwable.stackTraceToString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)

            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

class NostrViewModelFactory(
    private val activity: ComponentActivity
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val secretStore = SecretStore(activity)
        return NostrViewModel(NostrManager.instance, secretStore) as T
    }
}
