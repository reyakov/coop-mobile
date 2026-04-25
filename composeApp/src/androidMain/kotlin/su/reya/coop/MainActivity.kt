package su.reya.coop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Get database directory
        val dbDir = File(filesDir, "nostr")
        dbDir.mkdirs()

        setContent {
            App(dbDir.absolutePath)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}