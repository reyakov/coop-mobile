package su.reya.coop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class NostrForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nostr = NostrManager.instance

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isUserInApp(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification("Connecting to Nostr...")
        startForeground(1, notification)

        serviceScope.launch {
            try {
                val dbDir = File(filesDir, "nostr")
                dbDir.mkdirs()
                // Initialize Nostr client
                nostr.init(dbDir.absolutePath)
                // Connect to bootstrap relays
                nostr.connectBootstrapRelays()
                // Handle notifications
                nostr.handleLiteNotifications { event ->
                    if (!isUserInApp()) {
                        showNewMessageNotification(event.content())
                    }
                }
            } catch (e: Exception) {
                println("Failed to start Nostr in background: ${e.message}")
            }
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "nostr_service",
            "Nostr Background Service",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, "nostr_service")
            .setContentTitle("Coop")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()
    }

    private fun showNewMessageNotification(message: String) {
        val notification = NotificationCompat.Builder(this, "nostr_service")
            .setContentTitle("New Message")
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
