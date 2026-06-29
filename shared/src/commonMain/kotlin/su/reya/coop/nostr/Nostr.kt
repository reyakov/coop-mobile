package su.reya.coop.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientNotification
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventId
import rust.nostr.sdk.GossipConfig
import rust.nostr.sdk.Keys
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.LogLevel
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.NostrDatabase
import rust.nostr.sdk.NostrGossip
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMessageEnum
import rust.nostr.sdk.SignerAuthenticator
import rust.nostr.sdk.SleepWhenIdle
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnsignedEvent
import rust.nostr.sdk.initLogger
import su.reya.coop.UniversalSigner
import kotlin.time.Duration

object NostrManager {
    val instance = Nostr()

    val BOOTSTRAP_RELAYS = listOf(
        "wss://relay.primal.net",
        "wss://relay.ditto.pub",
        "wss://user.kindpag.es",
    )

    val INDEXER_RELAY = listOf(
        "wss://indexer.coracle.social",
    )

    val ALL_RELAYS = BOOTSTRAP_RELAYS + INDEXER_RELAY
}

class Nostr {
    var client: Client? = null
        private set
    var signer: UniversalSigner = UniversalSigner(Keys.generate())
        private set

    val messages = MessageManager(this)
    val profiles = ProfileManager(this)
    val relays = RelayManager(this)

    private val isInitialized = MutableStateFlow(false)

    private val _newEvents = MutableSharedFlow<UnsignedEvent>(extraBufferCapacity = 100)
    val newEvents = _newEvents.asSharedFlow()

    suspend fun emitNewEvent(event: UnsignedEvent) {
        _newEvents.emit(event)
    }

    suspend fun init(
        dbPath: String,
        logLevel: LogLevel = LogLevel.WARN
    ) {
        try {
            if (isInitialized.value) return

            // Initialize the logger for nostr client
            initLogger(logLevel)

            // Initialize configurations for nostr client
            val lmdb = NostrDatabase.lmdb(dbPath)
            val gossip = NostrGossip.inMemory()
            val authenticator = SignerAuthenticator(signer)
            val idleTimeout = Duration.parse("5m")

            client =
                ClientBuilder()
                    .authenticator(authenticator)
                    .database(lmdb)
                    .gossip(gossip)
                    .gossipConfig(
                        GossipConfig()
                            .noBackgroundRefresh()
                            .fetchTimeout(Duration.parse("2s"))
                    )
                    .verifySubscriptions(false)
                    .sleepWhenIdle(SleepWhenIdle.Enabled(idleTimeout))
                    .build()

            isInitialized.value = true
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize Nostr client: ${e.message}", e)
        }
    }

    suspend fun waitUntilInitialized() {
        isInitialized.first { it }
    }

    suspend fun connectBootstrapRelays() {
        relays.connectBootstrapRelays()
    }

    suspend fun reconnect() {
        relays.reconnect()
    }

    suspend fun disconnect() {
        relays.disconnect()
    }

    suspend fun prune() {
        try {
            client?.database()?.wipe()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to prune database: ${e.message}", e)
        }
    }

    suspend fun setSigner(new: AsyncNostrSigner) {
        try {
            signer.switch(new)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to set signer: ${e.message}", e)
        }
    }

    fun isSignedByUser(event: Event): Boolean {
        return try {
            signer.currentUser == event.author()
        } catch (e: Exception) {
            println("Failed to check if event is signed by user: ${e.message}")
            false
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun handleNotifications(
        onMetadataUpdate: (PublicKey, Metadata) -> Unit,
        onContactListUpdate: (List<PublicKey>) -> Unit,
        onNewMessage: (UnsignedEvent) -> Unit,
    ) = supervisorScope {
        val now = Timestamp.now()
        val processedEvent = mutableSetOf<EventId>()
        val notifications = client?.notifications() ?: return@supervisorScope

        val giftWrapQueue = Channel<Event>(Channel.UNLIMITED)
        var processedCount = 0
        var eoseReceived = false

        launch(Dispatchers.Default) {
            for (event in giftWrapQueue) {
                val rumor = messages.extractRumor(event)
                processedCount++

                // Trigger new message notification
                if (rumor != null) {
                    if (rumor.createdAt().asSecs() >= now.asSecs()) {
                        onNewMessage(rumor)
                    }
                }

                // Update sync state
                messages.updateSyncState {
                    it.copy(
                        processedCount = processedCount,
                        isSyncing = !eoseReceived || !giftWrapQueue.isEmpty
                    )
                }
            }
        }

        while (true) {
            val notification = notifications.next() ?: continue

            when (notification) {
                is ClientNotification.Message -> {
                    val relayUrl = notification.relayUrl

                    when (val message = notification.message.asEnum()) {
                        is RelayMessageEnum.EventMsg -> {
                            val event = message.event

                            // Prevent processing duplicate events
                            if (processedEvent.contains(event.id())) continue
                            processedEvent.add(event.id())

                            when (event.kind().asStd()) {
                                KindStandard.METADATA -> {
                                    try {
                                        val metadata = Metadata.fromJson(event.content())
                                        onMetadataUpdate(event.author(), metadata)
                                    } catch (e: Exception) {
                                        println("Failed to parse metadata: $e")
                                    }
                                }

                                KindStandard.CONTACT_LIST -> {
                                    if (isSignedByUser(event = event)) {
                                        val pubkeys = event.tags().publicKeys()
                                        // Get mutual contacts
                                        profiles.getMutualContacts(pubkeys)
                                        // Emit contact list update
                                        onContactListUpdate(pubkeys)
                                    }
                                }

                                KindStandard.INBOX_RELAYS -> {
                                    // Get all gift wrap events for the current user
                                    if (isSignedByUser(event = event)) {
                                        messages.getUserMessages(msgRelayList = event)
                                    }
                                }

                                KindStandard.GIFT_WRAP -> {
                                    giftWrapQueue.send(event)
                                }

                                else -> {}
                            }
                        }

                        is RelayMessageEnum.EndOfStoredEvents -> {
                            if (message.subscriptionId == "gift-wraps") {
                                eoseReceived = true
                                if (giftWrapQueue.isEmpty) {
                                    messages.updateSyncState { it.copy(isSyncing = false) }
                                }
                            }
                        }

                        is RelayMessageEnum.Ok -> {
                            if (messages.sentEvents.containsKey(message.eventId)) {
                                val currentRelays =
                                    messages.sentEvents[message.eventId] ?: emptyList()
                                messages.sentEvents[message.eventId] = currentRelays + relayUrl
                            }
                        }

                        else -> {
                            /* Ignore other message types */
                        }
                    }
                }

                is ClientNotification.Shutdown -> {
                    break
                }

                else -> {
                    /* Ignore other message types */
                }
            }
        }
    }
}
