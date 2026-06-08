package su.reya.coop

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidExternalSignerLauncher(activity: ComponentActivity) : ExternalSignerLauncher {
    private var callback: ((String?) -> Unit)? = null

    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val intent = result.data
            val result = intent?.getStringExtra("signature")
                ?: intent?.getStringExtra("public_key")
                ?: intent?.getStringExtra("content")
                ?: intent?.dataString

            callback?.invoke(result)
            callback = null
        }

    override suspend fun launch(
        content: String,
        type: String,
        pubkey: String?,
        id: String?
    ): String? =
        suspendCancellableCoroutine { continuation ->
            callback = { continuation.resume(it) }

            val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:$content".toUri())
            intent.putExtra("type", type)
            pubkey?.let { intent.putExtra("pubkey", it) }
            id?.let { intent.putExtra("id", it) }

            try {
                launcher.launch(intent)
            } catch (e: Exception) {
                callback?.invoke(null)
            }
        }
}