package su.reya.coop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
