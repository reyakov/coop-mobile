package su.reya.coop

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ExternalSignerLauncher(
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pendingResult: CompletableDeferred<ActivityResult>? = null
    private val mutex = Mutex()

    fun register(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    suspend fun launch(intent: Intent): ActivityResult = mutex.withLock {
        withContext(mainDispatcher) {
            val deferred = CompletableDeferred<ActivityResult>()
            pendingResult = deferred
            launcher?.launch(intent) ?: throw IllegalStateException("Signer not registered")
            deferred.await()
        }
    }


    fun onResult(result: ActivityResult) {
        pendingResult?.complete(result)
        pendingResult = null
    }
}
