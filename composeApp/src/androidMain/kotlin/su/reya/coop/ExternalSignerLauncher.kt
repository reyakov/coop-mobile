package su.reya.coop

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CompletableDeferred

class ExternalSignerLauncher {
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pendingResult: CompletableDeferred<ActivityResult>? = null

    fun register(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    suspend fun launch(intent: Intent): ActivityResult {
        val deferred = CompletableDeferred<ActivityResult>()
        pendingResult = deferred
        launcher?.launch(intent)
            ?: throw IllegalStateException("ExternalSignerLauncher not registered")
        return deferred.await()
    }

    fun onResult(result: ActivityResult) {
        pendingResult?.complete(result)
        pendingResult = null
    }
}
