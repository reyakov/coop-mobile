package su.reya.coop

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import rust.nostr.sdk.AckPolicy
import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientNotification
import rust.nostr.sdk.Contact
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.GossipConfig
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.LogLevel
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.MetadataRecord
import rust.nostr.sdk.NostrDatabase
import rust.nostr.sdk.NostrGossip
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayCapabilities
import rust.nostr.sdk.RelayMessageEnum
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.ReqExitPolicy
import rust.nostr.sdk.ReqTarget
import rust.nostr.sdk.SendEventTarget
import rust.nostr.sdk.SleepWhenIdle
import rust.nostr.sdk.SubscribeAutoCloseOptions
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnsignedEvent
import rust.nostr.sdk.UnwrappedGift
import rust.nostr.sdk.initLogger
import rust.nostr.sdk.nip17ExtractRelayList
import kotlin.time.Duration

class Nostr {
    var client: Client? = null
        private set
    var signer: UniversalSigner = UniversalSigner(Keys.generate())
        private set
    var deviceSigner: AsyncNostrSigner? = null
        private set
    var contactList: List<PublicKey> = emptyList()
        private set

    suspend fun init(dbPath: String) {
        try {
            // Initialize the logger for nostr client
            initLogger(LogLevel.DEBUG)

            val lmdb = NostrDatabase.lmdb(dbPath)
            val gossip = NostrGossip.inMemory()
            val idleTimeout = Duration.parse("5m")
            val httpClient = HttpClient {
                install(WebSockets)
            }

            client =
                ClientBuilder()
                    .signer(signer)
                    .websocketTransport(CoopWebSocketClient(httpClient))
                    .database(lmdb)
                    .gossip(gossip)
                    .gossipConfig(GossipConfig().noBackgroundRefresh())
                    .verifySubscriptions(false)
                    .automaticAuthentication(true)
                    .sleepWhenIdle(SleepWhenIdle.Enabled(idleTimeout))
                    .build()

            // Bootstrap relays
            client?.addRelay(RelayUrl.parse("wss://relay.primal.net"))
            client?.addRelay(RelayUrl.parse("wss://user.kindpag.es"))

            // Indexer relay for NIP-65 discovery
            client?.addRelay(
                url = RelayUrl.parse("wss://indexer.coracle.social"),
                capabilities = RelayCapabilities.gossip()
            )

            // Connect to all bootstrap relays and wait for all connections to be established
            client?.connect(Duration.parse("3s"))
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize Nostr client: ${e.message}", e)
        }
    }

    suspend fun disconnect() {
        client?.shutdown()
    }

    fun exit() {
        deviceSigner = null
        contactList = emptyList()
    }

    suspend fun setSigner(keys: AsyncNostrSigner) {
        try {
            signer.switch(keys)
            // Fetch metadata for current user
            getUserMetadata()
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

    suspend fun getUserMetadata() {
        try {
            val author = signer.currentUser ?: throw IllegalStateException("User not signed in")

            // Get the latest metadata event
            val metadataFilter =
                Filter().kind(Kind.fromStd(KindStandard.METADATA)).author(author).limit(1u)

            // Get the latest contact list event
            val contactFilter =
                Filter().kind(Kind.fromStd(KindStandard.CONTACT_LIST)).author(author).limit(1u)

            // Get the latest messaging relay list event
            val msgRelayFilter =
                Filter().kind(Kind.fromStd(KindStandard.INBOX_RELAYS)).author(author).limit(1u)

            // Construct a target that includes all filters
            val target = ReqTarget.auto(listOf(metadataFilter, contactFilter, msgRelayFilter))
            val opts = SubscribeAutoCloseOptions().exitPolicy(ReqExitPolicy.ExitOnEose)

            client?.subscribe(target = target, id = "user-metadata", closeOn = opts)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch user metadata: ${e.message}", e)
        }
    }

    suspend fun getUserMessages(msgRelayList: Event) {
        try {
            val author = signer.currentUser ?: throw IllegalStateException("User not signed in")
            val relays = nip17ExtractRelayList(msgRelayList)

            // Ensure relay connections
            relays.forEach { relay ->
                client?.addRelay(relay)
                client?.connectRelay(relay)
            }

            // Construct a filter for gift wrap events
            val filter = Filter().kind(Kind.fromStd(KindStandard.GIFT_WRAP)).pubkey(author)
            val target = mutableMapOf<RelayUrl, List<Filter>>()
            relays.forEach { relay ->
                target[relay] = listOf(filter)
            }

            client?.subscribe(
                target = ReqTarget.manual(target),
                id = "user-messages"
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch user messages: ${e.message}", e)
        }
    }

    suspend fun handleNotifications(onMetadataUpdate: (PublicKey, Metadata) -> Unit) {
        val now = Timestamp.now()
        val processedEvent = mutableSetOf<EventId>()
        val notifications = client?.notifications() ?: return

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

                            if (event.kind().asStd()?.equals(KindStandard.METADATA) == true) {
                                try {
                                    val metadata = Metadata.fromJson(event.content())
                                    onMetadataUpdate(event.author(), metadata)
                                } catch (e: Exception) {
                                    println("Failed to parse metadata: $e")
                                }
                            }

                            if (event.kind().asStd()?.equals(KindStandard.INBOX_RELAYS) == true) {
                                if (isSignedByUser(event = event)) {
                                    getUserMessages(msgRelayList = event)
                                }
                            }

                            if (event.kind().asStd()?.equals(KindStandard.GIFT_WRAP) == true) {
                                try {
                                    val rumor = extractRumor(event)
                                    // TODO: Handle rumor
                                } catch (e: Exception) {
                                    println("Failed to extract rumor: $e")
                                }
                            }
                        }

                        is RelayMessageEnum.EndOfStoredEvents -> {
                            val subscriptionId = message.subscriptionId
                            // TODO: Handle end of stored events
                        }

                        else -> {
                            /* Ignore other message types */
                        }
                    }
                }

                is ClientNotification.NewEvent -> {
                    // TODO: Handle new event
                }

                is ClientNotification.Shutdown -> {
                    break
                }
            }
        }
    }

    private suspend fun getCachedRumor(giftId: EventId): UnsignedEvent? {
        try {
            val filter = Filter().identifier(giftId.toBech32())
            val event = client?.database()?.query(filter)?.first()

            return event?.content()?.let { UnsignedEvent.fromJson(it) }
        } catch (e: Exception) {
            println("Failed to get cached rumor: ${e.message}")
            return null
        }
    }

    private suspend fun setCachedRumor(giftId: EventId, rumor: UnsignedEvent) {
        if (rumor.id() == null) return

        try {
            val rngKeys = Keys.generate()
            val kind = Kind.fromStd(KindStandard.APPLICATION_SPECIFIC_DATA);
            val tags = listOf(Tag.identifier(giftId.toBech32()), Tag.event(rumor.id()!!))
            val event = EventBuilder(kind, rumor.asJson()).tags(tags).signWithKeys(rngKeys)

            client?.database()?.saveEvent(event)
            client?.database()?.saveEvent(rumor.signWithKeys(rngKeys))
        } catch (e: Exception) {
            println("Failed to set cached rumor: ${e.message}")
        }
    }

    private suspend fun extractRumor(event: Event): UnsignedEvent? {
        // Check if the rumor is already cached
        val cachedRumor = getCachedRumor(event.id())
        if (cachedRumor != null) return cachedRumor

        // Get all signers
        val signers = listOfNotNull(signer, deviceSigner)
        if (signers.isEmpty()) return null

        // Try to unwrap the gift with each signer
        for (signer in signers) {
            try {
                // TODO: custom unwrapping logic
                val gift = UnwrappedGift.fromGiftWrapAsync(signer = signer, giftWrap = event)
                val rumor = gift.rumor()
                // Save the rumor to the database
                setCachedRumor(event.id(), rumor)
                // Return the rumor
                return rumor
            } catch (e: Exception) {
                println("Failed to unwrap gift: ${e.message}")
                continue
            }
        }

        return null
    }

    private fun conversationId(rumor: UnsignedEvent): Long {
        val pubkeys: MutableList<PublicKey> = rumor.tags().publicKeys().toMutableList()
        pubkeys.add(rumor.author())

        val uniqueSortedKeys = pubkeys
            .map { it.toHex() }
            .distinct()
            .sorted()

        return uniqueSortedKeys.hashCode().toLong()
    }


    private suspend fun getDefaultRelayList(): Map<RelayUrl, RelayMetadata> {
        // Construct a list of relays
        val relayList = mapOf<RelayUrl, RelayMetadata>(
            RelayUrl.parse("wss://relay.damus.io") to RelayMetadata.READ,
            RelayUrl.parse("wss://relay.primal.net") to RelayMetadata.READ,
            RelayUrl.parse("wss://relay.nostr.net") to RelayMetadata.WRITE,
            RelayUrl.parse("wss://nostr.superfriends.online") to RelayMetadata.WRITE
        )

        // Ensure all relays are added and connected
        relayList.forEach { (relay, metadata) ->
            client?.addRelay(
                url = relay,
                capabilities =
                    if (metadata == RelayMetadata.READ) RelayCapabilities.read()
                    else if (metadata == RelayMetadata.WRITE) RelayCapabilities.write()
                    else RelayCapabilities.none()
            )
            client?.connectRelay(relay)
        }

        return relayList
    }

    private suspend fun getMsgRelayList(): List<RelayUrl> {
        // Construct a list of messaging relays
        val msgRelayList = listOf(
            RelayUrl.parse("wss://relay.0xchat.com"),
            RelayUrl.parse("wss://nip17.com"),
        )

        // Ensure all relays are added and connected
        msgRelayList.forEach { relay ->
            client?.addRelay(relay, RelayCapabilities.none())
            client?.connectRelay(relay)
        }

        return msgRelayList
    }

    suspend fun createIdentity(keys: Keys, name: String, bio: String, picture: String?) {
        // Send relay list event
        val relayList = getDefaultRelayList()
        val relayListEvent = EventBuilder.relayList(relayList).signWithKeys(keys);

        client?.sendEvent(
            event = relayListEvent,
            target = SendEventTarget.broadcast(),
            ackPolicy = AckPolicy.all(),
            okTimeout = Duration.parse("3s")
        )

        // Send messaging relay list event
        val msgRelayList = getMsgRelayList()
        val msgRelayListEvent = EventBuilder.nip17RelayList(msgRelayList).signWithKeys(keys)

        client?.sendEvent(
            event = msgRelayListEvent,
            target = SendEventTarget.toNip65(),
            ackPolicy = AckPolicy.none()
        )

        // Send metadata event
        val metadata =
            Metadata.fromRecord(MetadataRecord(name = name, about = bio, picture = picture))
        val metadataEvent = EventBuilder.metadata(metadata).signWithKeys(keys)

        client?.sendEvent(
            event = metadataEvent,
            target = SendEventTarget.toNip65(),
            ackPolicy = AckPolicy.none()
        )

        // Send contact list event
        val defaultContact =
            listOf(Contact(publicKey = PublicKey.parse("npub1j3rz3ndl902lya6ywxvy5c983lxs8mpukqnx4pa4lt5wrykwl5ys7wpw3x")))
        val contactListEvent = EventBuilder.contactList(defaultContact).signWithKeys(keys)

        client?.sendEvent(
            event = contactListEvent,
            target = SendEventTarget.toNip65(),
            ackPolicy = AckPolicy.none()
        )

        setSigner(keys)
    }

    suspend fun fetchMetadataBatch(keys: List<PublicKey>) {
        try {
            val limit = keys.size.toULong();
            val opts = SubscribeAutoCloseOptions().exitPolicy(ReqExitPolicy.ExitOnEose)

            // Construct a filter for metadata events
            val filter = Filter()
                .kind(Kind.fromStd(KindStandard.METADATA))
                .authors(keys)
                .limit(limit)

            // Construct a target that includes all filters
            val target =
                ReqTarget.manual(
                    mapOf(
                        RelayUrl.parse("wss://user.kindpag.es") to listOf(filter),
                        RelayUrl.parse("wss://relay.primal.net") to listOf(filter)
                    )
                )

            client?.subscribe(target = target, id = "metadata-reqs", closeOn = opts)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch metadata batch: ${e.message}", e)
        }
    }

    suspend fun getChatRooms(): Set<Room>? {
        try {
            val userPubkey = signer.currentUser ?: throw IllegalStateException("User not signed in")
            val kind = Kind.fromStd(KindStandard.PRIVATE_DIRECT_MESSAGE)

            // Get all events sent by the user
            val sendFilter = Filter().kind(kind).author(userPubkey)
            val sendEvents = client?.database()?.query(sendFilter);

            // Get all events sent to the user
            val recvFilter = Filter().kind(kind).pubkey(userPubkey)
            val recvEvents = client?.database()?.query(recvFilter);

            // Collect all events
            val events = sendEvents?.merge(recvEvents!!)?.toVec();
            val rooms: MutableSet<Room> = mutableSetOf()

            events
                ?.filter { it.tags().publicKeys().isNotEmpty() }
                ?.sortedByDescending { it.createdAt().asSecs() }
                ?.forEach { event ->
                    val room = Room.new(rumor = event, userPubkey = userPubkey)

                    // Check if the room already exists
                    if (rooms.contains(room)) return@forEach

                    val filter =
                        Filter().kind(kind).author(userPubkey).pubkeys(room.members.toList());

                    // Check if the user is interacting with the room's members
                    val isInteracting = client?.database()?.query(filter)?.isEmpty() == false;

                    // Check if the room's members are in the contact list
                    val isContact = contactList.containsAll(room.members)

                    // Set the room kind based on interaction status
                    if (isInteracting || isContact) {
                        room.setKind(RoomKind.Ongoing)
                    }

                    rooms.add(room)
                }

            return rooms
        } catch (e: Exception) {
            println("Failed to get chat rooms: ${e.message}")
            return null
        }
    }

    suspend fun getChatRoomMessages(members: List<PublicKey>): List<Event> {
        try {
            val userPubkey = signer.currentUser ?: throw IllegalStateException("User not signed in")

            val kind = Kind.fromStd(KindStandard.PRIVATE_DIRECT_MESSAGE)
            val sendFilter = Filter().kind(kind).author(userPubkey).pubkeys(members)
            val recvFilter = Filter().kind(kind).pubkey(userPubkey).authors(members)

            val sendEvents = client?.database()?.query(sendFilter)
            val recvEvents = client?.database()?.query(recvFilter)
            val events = sendEvents?.merge(recvEvents!!)?.toVec()

            return events ?: emptyList()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get chat room messages: ${e.message}", e)
        }
    }
}
