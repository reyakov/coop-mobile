package su.reya.coop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val nostr = Nostr()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Get database directory
        val dbDir = File(filesDir, "nostr")
        dbDir.mkdirs()

        // Initialize nostr client
        nostr.init(dbDir.absolutePath)

        // Connect to bootstrap relays
        lifecycleScope.launch {
            nostr.connect()
        }

        setContent {
            App()
        }

    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}