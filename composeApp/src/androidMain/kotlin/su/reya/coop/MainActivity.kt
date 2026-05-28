package su.reya.coop

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import su.reya.coop.coop.storage.SecretStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, NostrForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val roomId = intent.getLongExtra("room_id", -1L)
        val secretStore = SecretStore(this)
        val viewModel = NostrViewModel(NostrManager.instance, secretStore)

        splashScreen.setKeepOnScreenCondition {
            viewModel.emptySecret.value == null
        }

        setContent {
            App(
                viewModel = viewModel,
                openRoomId = if (roomId != -1L) roomId else null
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
