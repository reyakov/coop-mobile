package su.reya.coop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class NostrForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nostr by lazy { NostrManager.instance }
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isUserInApp(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        // Check if the service is already running
        if (notificationJob?.isActive == true) return START_STICKY

        // Start the Nostr client
        notificationJob = serviceScope.launch {
            try {
                Log.d("Coop", "Starting Nostr in background")

                // Create a database directory
                val dbDir = File(filesDir, "nostr")
                dbDir.mkdirs()

                // Initialize Nostr client
                try {
                    nostr.init(dbDir.absolutePath)
                } catch (e: Exception) {
                    throw IllegalStateException("Failed to initialize Nostr Client", e)
                }
                // Connect to bootstrap relays
                nostr.connectBootstrapRelays()
                // Handle notifications
                nostr.handleNotifications(
                    onMetadataUpdate = { pubkey, metadata ->
                        serviceScope.launch { nostr.emitMetadataUpdate(pubkey, metadata) }
                    },
                    onContactListUpdate = { contacts ->
                        serviceScope.launch { nostr.emitContactListUpdate(contacts) }
                    },
                    onSubscriptionClose = {
                        serviceScope.launch { nostr.emitSubscriptionClosed() }
                    },
                    onNewMessage = { event ->
                        serviceScope.launch {
                            if (!isUserInApp()) {
                                showNewMessageNotification(event.roomId(), event.content())
                            }
                            nostr.emitNewEvent(event)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("Coop", "Failed to start Nostr", e)
            }
        }

        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }


    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            "nostr_service",
            "Nostr Background Status",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
        }
        manager?.createNotificationChannel(serviceChannel)

        val messageChannel = NotificationChannel(
            "nostr_messages",
            "New Messages",
            NotificationManager.IMPORTANCE_HIGH
        )
        manager?.createNotificationChannel(messageChannel)
    }

    private fun createNotification(content: String? = null): Notification {
        val builder = NotificationCompat.Builder(this, "nostr_service")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (content != null) {
            builder.setContentTitle("Coop")
            builder.setContentText(content)
        } else {
            builder.setContentTitle("Coop is active")
        }

        return builder.build()
    }

    private fun showNewMessageNotification(roomId: Long, message: String) {
        val deepLinkUri = "coop://chat/$roomId".toUri()

        val intent = Intent(
            Intent.ACTION_VIEW,
            deepLinkUri,
            this,
            MainActivity::class.java
        )

        val pendingIntent = PendingIntent.getActivity(
            this,
            roomId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "nostr_messages")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("You received a new message")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
