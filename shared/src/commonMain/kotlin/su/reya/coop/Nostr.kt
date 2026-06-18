package su.reya.coop

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import rust.nostr.sdk.AckPolicy
import rust.nostr.sdk.Alphabet
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
import rust.nostr.sdk.Nip05Address
import rust.nostr.sdk.Nip05Profile
import rust.nostr.sdk.NostrDatabase
import rust.nostr.sdk.NostrGossip
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayCapabilities
import rust.nostr.sdk.RelayMessageEnum
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayStatus
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.ReqExitPolicy
import rust.nostr.sdk.ReqTarget
import rust.nostr.sdk.SendEventTarget
import rust.nostr.sdk.SignerAuthenticator
import rust.nostr.sdk.SingleLetterTag
import rust.nostr.sdk.SleepWhenIdle
import rust.nostr.sdk.SubscribeAutoCloseOptions
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnsignedEvent
import rust.nostr.sdk.extractRelayList
import rust.nostr.sdk.initLogger
import rust.nostr.sdk.nip17ExtractRelayList
import rust.nostr.sdk.nip59MakeGiftWrapAsync
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object NostrManager {
    val instance = Nostr()

    val BOOTSTRAP_RELAYS = listOf(
        "wss://relay.primal.net",
        "wss://purplepag.es"
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
    var sentEvents: MutableMap<EventId, List<RelayUrl>> = mutableMapOf()
        private set
    var rumorMap: MutableMap<EventId, EventId> = mutableMapOf()
        private set

    private val isInitialized = MutableStateFlow(false)

    private val _newEvents = MutableSharedFlow<UnsignedEvent>(extraBufferCapacity = 100)
    val newEvents = _newEvents.asSharedFlow()

    private val _metadataUpdates =
        MutableSharedFlow<Pair<PublicKey, Metadata>>(extraBufferCapacity = 100)
    val metadataUpdates = _metadataUpdates.asSharedFlow()

    private val _contactListUpdates = MutableSharedFlow<List<PublicKey>>(extraBufferCapacity = 100)
    val contactListUpdates = _contactListUpdates.asSharedFlow()

    private val _subscriptionClosed = MutableSharedFlow<Unit>(extraBufferCapacity = 10)
    val subscriptionClosed = _subscriptionClosed.asSharedFlow()

    suspend fun emitNewEvent(event: UnsignedEvent) = _newEvents.emit(event)

    suspend fun emitSubscriptionClosed() = _subscriptionClosed.emit(Unit)

    suspend fun emitMetadataUpdate(pubkey: PublicKey, metadata: Metadata) =
        _metadataUpdates.emit(pubkey to metadata)

    suspend fun emitContactListUpdate(contacts: List<PublicKey>) =
        _contactListUpdates.emit(contacts)

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
        NostrManager.BOOTSTRAP_RELAYS.forEach { url ->
            client?.addRelay(RelayUrl.parse(url))
        }
        NostrManager.INDEXER_RELAY.forEach { url ->
            client?.addRelay(
                url = RelayUrl.parse(url),
                capabilities = RelayCapabilities.gossip()
            )
        }
        // Connect to all bootstrap relays
        client?.connect()
    }

    suspend fun reconnect() {
        NostrManager.ALL_RELAYS.forEach { url ->
            try {
                client?.relay(RelayUrl.parse(url)).let { relay ->
                    if (relay != null) {
                        if (relay.status() != RelayStatus.CONNECTED) {
                            relay.connect()
                        }
                    }
                }
            } catch (e: Exception) {
                println("Failed to reconnect relay: ${e.message}")
            }
        }
    }

    suspend fun disconnect() {
        NostrManager.ALL_RELAYS.forEach { url ->
            try {
                client?.disconnectRelay(RelayUrl.parse(url))
            } catch (e: Exception) {
                println("Failed to disconnect relay: ${e.message}")
            }
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
                id = "gift-wraps"
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch user messages: ${e.message}", e)
        }
    }

    suspend fun handleNotifications(
        onMetadataUpdate: (PublicKey, Metadata) -> Unit,
        onContactListUpdate: (List<PublicKey>) -> Unit,
        onNewMessage: (UnsignedEvent) -> Unit,
        onSubscriptionClose: () -> Unit,
    ) = supervisorScope {
        val now = Timestamp.now()
        val processedEvent = mutableSetOf<EventId>()
        val notifications = client?.notifications() ?: return@supervisorScope

        var eoseTrackerJob: Job? = null

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

                            if (event.kind().asStd()?.equals(KindStandard.CONTACT_LIST) == true) {
                                if (isSignedByUser(event = event)) {
                                    onContactListUpdate(event.tags().publicKeys())
                                }
                            }

                            if (event.kind().asStd()?.equals(KindStandard.INBOX_RELAYS) == true) {
                                // Get all gift wrap events for the current user
                                if (isSignedByUser(event = event)) {
                                    getUserMessages(msgRelayList = event)
                                }
                            }

                            if (event.kind().asStd()?.equals(KindStandard.GIFT_WRAP) == true) {
                                val rumor = extractRumor(event)

                                // Logic to notify UI after processing
                                // Cancel previous tracker if it exists
                                eoseTrackerJob?.cancel()
                                // Start a new tracker
                                eoseTrackerJob = launch {
                                    delay(10000.milliseconds) // Wait for 10 seconds
                                    onSubscriptionClose()
                                }

                                // Handle new message
                                rumor?.createdAt()?.asSecs()?.let {
                                    if (it >= now.asSecs()) {
                                        onNewMessage(rumor)
                                    }
                                }
                            }
                        }

                        is RelayMessageEnum.EndOfStoredEvents -> {
                            val subscriptionId = message.subscriptionId

                            if (subscriptionId == "gift-wraps") {
                                onSubscriptionClose()
                            }
                        }

                        is RelayMessageEnum.Ok -> {
                            if (sentEvents.containsKey(message.eventId)) {
                                val currentRelays = sentEvents[message.eventId] ?: emptyList()
                                sentEvents[message.eventId] = currentRelays + relayUrl
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

    private suspend fun getCachedRumor(giftId: EventId): UnsignedEvent? {
        try {
            val filter = Filter().identifier(giftId.toHex())
            val event = client?.database()?.query(filter)?.first()

            return event?.content()?.let { UnsignedEvent.fromJson(it) }
        } catch (e: Throwable) {
            throw IllegalStateException("Failed to get cached rumor: ${e.message}", e)
        }
    }

    private suspend fun setCachedRumor(giftId: EventId, rumor: UnsignedEvent) {
        try {
            // Construct the room id
            val roomId = rumor.roomId()

            // Construct reference tags
            val tags = listOf(
                Tag.identifier(giftId.toHex()),
                Tag.publicKey(rumor.author()),
                Tag.event(rumor.id()!!),
                Tag.custom("r", listOf(roomId.toString())),
                Tag.custom("k", listOf("14"))
            )

            // Set event kind
            val kind = Kind.fromStd(KindStandard.APPLICATION_SPECIFIC_DATA)

            // Construct event
            val event = EventBuilder(kind, rumor.asJson())
                .tags(tags)
                .finalizeAsync(Keys.generate())

            client?.database()?.saveEvent(event)
        } catch (e: Throwable) {
            println("Failed to set cached rumor: ${e.message}")
        }
    }

    private suspend fun extractRumor(event: Event): UnsignedEvent? {
        try {
            // Gift wrap must have at least one 'p' tag
            if (event.tags().publicKeys().isEmpty()) {
                println("No recipient tags found.")
                return null
            }

            // Event must be a gift wrap
            if (event.kind().asStd().let { it != KindStandard.GIFT_WRAP }) {
                println("Event is not a gift wrap.")
                return null
            }

            // Check if the rumor is already cached
            val cachedRumor = getCachedRumor(event.id())
            if (cachedRumor != null) return cachedRumor

            // Decrypt the gift wrap event
            val seal = signer.nip44DecryptAsync(event.author(), event.content())
            val sealEvent = Event.fromJson(seal)

            // Verify seal event
            if (!sealEvent.verify()) {
                println("Failed to verify seal event.")
                return null
            }

            // Decrypt the rumor
            val rumor = signer.nip44DecryptAsync(sealEvent.author(), sealEvent.content())
            val unsignedEvent = UnsignedEvent.fromJson(rumor)

            // Ensure the rumor author matches the seal
            if (unsignedEvent.author() != sealEvent.author()) {
                println("Author mismatch.")
                return null
            }

            // Cache the rumor for later use
            setCachedRumor(event.id(), unsignedEvent)

            return unsignedEvent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("Failed to unwrap gift ${event.id().toHex()}: ${e.message}")
            return null
        }
    }

    private suspend fun getDefaultRelayList(): Map<RelayUrl, RelayMetadata> {
        // Construct a list of relays
        val relayList = mapOf(
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
                    when (metadata) {
                        RelayMetadata.READ -> RelayCapabilities.read()
                        RelayMetadata.WRITE -> RelayCapabilities.write()
                    }
            )
            client?.connectRelay(relay)
        }

        return relayList
    }

    suspend fun getDefaultMsgRelayList(): List<RelayUrl> {
        // Construct a list of messaging relays
        val msgRelayList = listOf(
            RelayUrl.parse("wss://auth.nostr1.com"),
            RelayUrl.parse("wss://nip17.com"),
        )

        // Ensure all relays are added and connected
        msgRelayList.forEach { relay ->
            client?.addRelay(relay, RelayCapabilities.none())
            client?.connectRelay(relay)
        }

        return msgRelayList
    }

    suspend fun createIdentity(keys: Keys, name: String, bio: String?, picture: String?) {
        // Send relay list event
        val relayList = getDefaultRelayList()
        val relayListEvent = EventBuilder.relayList(relayList).finalizeAsync(keys)

        client?.sendEvent(
            event = relayListEvent,
            target = SendEventTarget.broadcast(),
            ackPolicy = AckPolicy.all(),
            okTimeout = Duration.parse("3s")
        )

        // Send messaging relay list event
        val msgRelayList = getDefaultMsgRelayList()
        val msgRelayListEvent = EventBuilder.nip17RelayList(msgRelayList).finalizeAsync(keys)

        client?.sendEvent(
            event = msgRelayListEvent,
            target = SendEventTarget.toNip65(),
            ackPolicy = AckPolicy.none()
        )

        // Send metadata event
        val metadata =
            Metadata.fromRecord(MetadataRecord(displayName = name, about = bio, picture = picture))
        val metadataEvent = EventBuilder.metadata(metadata).finalizeAsync(keys)

        client?.sendEvent(
            event = metadataEvent,
            target = SendEventTarget.broadcast(),
            ackPolicy = AckPolicy.none()
        )

        // Send contact list event
        val defaultContact =
            Contact(PublicKey.parse("npub1j3rz3ndl902lya6ywxvy5c983lxs8mpukqnx4pa4lt5wrykwl5ys7wpw3x"))
        val contactListEvent = EventBuilder.contactList(listOf(defaultContact)).finalizeAsync(keys)

        client?.sendEvent(
            event = contactListEvent,
            target = SendEventTarget.toNip65(),
            ackPolicy = AckPolicy.none()
        )

        setSigner(keys)
    }

    suspend fun updateProfile(
        name: String? = null,
        bio: String? = null,
        picture: String? = null
    ): Metadata {
        val currentUser = signer.currentUser ?: throw IllegalStateException("User not signed in")

        try {
            val record = getLatestMetadata(currentUser)?.asRecord() ?: MetadataRecord()
            val newRecord = record.copy(
                displayName = name ?: record.displayName,
                about = bio ?: record.about,
                picture = picture ?: record.picture
            )
            val newMetadata = Metadata.fromRecord(newRecord)
            val event = EventBuilder.metadata(newMetadata).finalizeAsync(signer)

            client?.sendEvent(
                event = event,
                target = SendEventTarget.broadcast(),
                ackPolicy = AckPolicy.none()
            )

            return newMetadata
        } catch (e: Exception) {
            throw IllegalStateException("Failed to update identity: ${e.message}", e)
        }
    }

    private suspend fun getLatestMetadata(pubkey: PublicKey): Metadata? {
        return try {
            val kind = Kind.fromStd(KindStandard.METADATA)
            val filter = Filter().kind(kind).author(pubkey).limit(1u)
            val event = client?.database()?.query(filter)?.first() ?: return null

            Metadata.fromJson(event.content())
        } catch (e: Exception) {
            println("Failed to get latest metadata: ${e.message}")
            null
        }
    }

    suspend fun getAllCacheMetadata(): Map<PublicKey, Metadata> {
        try {
            val filter = Filter().kind(Kind.fromStd(KindStandard.METADATA)).limit(100u)
            val events = client?.database()?.query(filter)
            val results = mutableMapOf<PublicKey, Metadata>()

            events?.toVec()?.forEach { event ->
                try {
                    val metadata = Metadata.fromJson(event.content())
                    results[event.author()] = metadata
                } catch (e: Exception) {
                    println("Failed to parse metadata: $e")
                }
            }

            return results
        } catch (e: Exception) {
            println("Failed to get all cache metadata: ${e.message}")
            return emptyMap()
        }
    }

    suspend fun fetchMetadataBatch(keys: List<PublicKey>) {
        try {
            val limit = keys.size.toULong() * 4u
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
                        RelayUrl.parse("wss://purplepag.es") to listOf(filter),
                        RelayUrl.parse("wss://relay.primal.net") to listOf(filter),
                    )
                )

            client?.subscribe(target = target, closeOn = opts)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch metadata batch: ${e.message}", e)
        }
    }

    suspend fun setMsgRelays(urls: List<RelayUrl>) {
        try {
            val event = EventBuilder.nip17RelayList(urls).finalizeAsync(signer)

            client?.sendEvent(
                event = event,
                target = SendEventTarget.toNip65(),
                ackPolicy = AckPolicy.none(),
            )

            val kind = Kind.fromStd(KindStandard.INBOX_RELAYS)
            val filter = Filter().kind(kind).author(signer.currentUser!!).limit(1u)
            val target = ReqTarget.auto(listOf(filter))
            val opts = SubscribeAutoCloseOptions().exitPolicy(ReqExitPolicy.ExitOnEose)

            client?.subscribe(target = target, closeOn = opts)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to set msg relays: ${e.message}", e)
        }
    }

    suspend fun getMsgRelays(publicKey: PublicKey): List<RelayUrl> {
        try {
            val kind = Kind.fromStd(KindStandard.INBOX_RELAYS)
            val filter = Filter().kind(kind).author(publicKey).limit(1u)
            val events = client?.database()?.query(filter)

            return nip17ExtractRelayList(events?.toVec()?.firstOrNull() ?: return emptyList())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get msg relays: ${e.message}", e)
        }
    }

    suspend fun fetchMsgRelays(publicKey: PublicKey): List<RelayUrl> {
        try {
            val kind = Kind.fromStd(KindStandard.INBOX_RELAYS)
            val filter = Filter().kind(kind).author(publicKey).limit(1u)
            val target = ReqTarget.auto(listOf(filter))
            val events = client?.fetchEvents(target, timeout = Duration.parse("3s"))

            return nip17ExtractRelayList(events?.toVec()?.firstOrNull() ?: return emptyList())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch msg relays: ${e.message}", e)
        }
    }

    suspend fun getRelayList(publicKey: PublicKey): Map<RelayUrl, RelayMetadata?> {
        try {
            val kind = Kind.fromStd(KindStandard.RELAY_LIST)
            val filter = Filter().kind(kind).author(publicKey).limit(1u)
            val events = client?.database()?.query(filter)

            return extractRelayList(events?.toVec()?.firstOrNull() ?: return emptyMap())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get relay list: ${e.message}", e)
        }
    }

    suspend fun setRelaylist(relays: Map<RelayUrl, RelayMetadata?>) {
        try {
            val event = EventBuilder.relayList(relays).finalizeAsync(signer)

            client?.sendEvent(
                event = event,
                target = SendEventTarget.broadcast(),
                ackPolicy = AckPolicy.none(),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to set msg relays: ${e.message}", e)
        }
    }

    suspend fun setContactList(contacts: List<PublicKey>) {
        try {
            val contacts = contacts.map { Contact(it) }
            val event = EventBuilder.contactList(contacts).finalizeAsync(signer)

            client?.sendEvent(
                event = event,
                target = SendEventTarget.broadcast(),
                ackPolicy = AckPolicy.none(),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to set contact list: ${e.message}", e)
        }
    }

    suspend fun getChatRooms(): Set<Room>? {
        try {
            val userPubkey =
                signer.getPublicKeyAsync() ?: throw IllegalStateException("User not signed in")

            val kind = Kind.fromStd(KindStandard.APPLICATION_SPECIFIC_DATA)
            val kTag = SingleLetterTag.lowercase(Alphabet.K)

            // Get all events sent by the user
            val filter = Filter().kind(kind).pubkey(userPubkey).customTags(kTag, listOf("14", "dm"))
            val events = client?.database()?.query(filter)

            // Collect rooms
            val roomsMap: MutableMap<Long, Room> = mutableMapOf()

            events
                ?.toVec()
                ?.map { UnsignedEvent.fromJson(it.content()) }
                ?.filter { it.tags().publicKeys().isNotEmpty() }
                ?.forEach { event ->
                    val newRoom = Room.new(rumor = event, userPubkey = userPubkey)
                    val existingRoom = roomsMap[newRoom.id]

                    // Check if the room already exists
                    if (existingRoom == null || newRoom.createdAt.asSecs() > existingRoom.createdAt.asSecs()) {
                        val kind = Kind.fromStd(KindStandard.PRIVATE_DIRECT_MESSAGE)
                        val pubkeys = newRoom.members.toList()
                        val filter = Filter().kind(kind).author(userPubkey).pubkeys(pubkeys)

                        // Determine if it's an ongoing room
                        val isOngoing = client?.database()?.query(filter)?.isEmpty() ?: false

                        // Append room to map
                        roomsMap[newRoom.id] =
                            if (isOngoing) newRoom.copy(kind = RoomKind.Ongoing) else newRoom
                    }
                }

            return roomsMap.values.sortedByDescending { it.createdAt.asSecs() }.toSet()
        } catch (e: Exception) {
            println("Failed to get chat rooms: ${e.message}")
            return null
        }
    }

    suspend fun getChatRoomMessages(roomId: Long): List<UnsignedEvent> {
        try {
            val kind = Kind.fromStd(KindStandard.APPLICATION_SPECIFIC_DATA)
            val filter = Filter().kind(kind).reference(roomId.toString())
            val events = client?.database()?.query(filter)

            // Merge the events
            return events
                ?.toVec()
                ?.map { UnsignedEvent.fromJson(it.content()) }
                // Filter out events without public keys (receivers)
                ?.filter { it.tags().publicKeys().isNotEmpty() }
                ?.sortedByDescending { it.createdAt().asSecs() } ?: emptyList()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get chat room messages: ${e.message}", e)
        }
    }

    suspend fun chatRoomConnect(members: List<PublicKey>) {
        try {
            members.forEach { member ->
                val kind = Kind.fromStd(KindStandard.INBOX_RELAYS)
                val filter = Filter().kind(kind).author(member).limit(1u)

                val stream = client?.streamEvents(
                    target = ReqTarget.auto(listOf(filter)),
                    id = null,
                    timeout = Duration.parse("3s"),
                    policy = ReqExitPolicy.ExitOnEose
                )

                stream?.next()?.let { res ->
                    if (res.event != null) {
                        connectMsgRelays(res.event!!)
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch relays: ${e.message}", e)
        }
    }

    suspend fun connectMsgRelays(event: Event) {
        try {
            val urls = nip17ExtractRelayList(event)
            for (url in urls) {
                client?.addRelay(url, RelayCapabilities.gossip())
                client?.connectRelay(url)
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to connect to relays: ${e.message}", e)
        }
    }

    suspend fun sendMessage(
        to: Set<PublicKey>,
        content: String,
        subject: String? = null,
        replies: List<EventId> = emptyList(),
        onRumorCreated: ((UnsignedEvent) -> Unit)? = null,
    ) {
        try {
            val currentUser =
                signer.getPublicKeyAsync() ?: throw IllegalStateException("User not signed in")

            val tags = mutableListOf<Tag>()

            // Add a subject tag if provided
            if (subject != null) {
                tags.add(Tag.custom("subject", listOf(subject)))
            }

            // Add event tags for replies
            if (replies.isNotEmpty()) {
                replies.forEach { replyId ->
                    tags.add(Tag.event(replyId))
                }
            }

            // Add public key tags for each recipient
            to.forEach { pubkey ->
                tags.add(Tag.publicKey(pubkey))
            }

            for (receiver in setOf(currentUser) + to) {
                // Construct the rumor event
                // NEVER SIGN this event with the current user signer
                val rumor = EventBuilder(Kind.fromStd(KindStandard.PRIVATE_DIRECT_MESSAGE), content)
                    .tags(tags)
                    .finalizeUnsigned(currentUser)
                    .ensureId()

                // Emit the rumor to the chat screen
                if (receiver == currentUser) {
                    onRumorCreated?.invoke(rumor)
                }

                // Construct the gift wrap event
                val gift = nip59MakeGiftWrapAsync(
                    signer = signer,
                    receiverPubkey = receiver,
                    rumor = rumor,
                    extraTags = listOf(
                        Tag.custom("k", listOf("14"))
                    )
                )

                // Send the event to receiver's NIP-17 relays
                val output = client?.sendEvent(
                    event = gift,
                    target = SendEventTarget.toNip17(),
                    ackPolicy = AckPolicy.none(),
                    authenticationTimeout = Duration.parse("2s")
                )

                if (output != null) {
                    // Keep track of sent events
                    sentEvents[output.id] = emptyList()
                    if (rumor.id() != null) rumorMap[rumor.id()!!] = output.id

                    // Collect failed outputs
                    output.failed.forEach { (relayUrl, reason) ->
                        println("Failed to send event to relay $relayUrl: $reason")
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to send message: ${e.message}", e)
        }
    }

    suspend fun profileFromAddress(client: HttpClient, address: Nip05Address): Nip05Profile {
        try {
            val response: HttpResponse = client.get(address.url())
            val bodyString: String = response.body()

            return Nip05Profile.fromJson(address, bodyString)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch profile from address: ${e.message}", e)
        }
    }

    suspend fun searchByAddress(query: String): PublicKey {
        try {
            val address = Nip05Address.parse(query)
            val profile = profileFromAddress(HttpClient(), address)

            return profile.publicKey()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to search address: ${e.message}", e)
        }
    }

    suspend fun searchByNostr(query: String): List<PublicKey> {
        try {
            // Add search relay
            val searchRelay = RelayUrl.parse("wss://antiprimal.net")
            if (client?.relay(searchRelay) == null) {
                client?.addRelay(url = searchRelay, capabilities = RelayCapabilities.read())
                client?.connectRelay(searchRelay)
            }

            val kinds = listOf(Kind.fromStd(KindStandard.METADATA))
            val filter = Filter().kinds(kinds).search(query).limit(10u)
            val target = ReqTarget.manual(mapOf(searchRelay to listOf(filter)))

            val stream = client?.streamEvents(
                target = target,
                id = "search",
                timeout = Duration.parse("3s"),
                policy = ReqExitPolicy.ExitOnEose
            )

            // Collect the results
            val results = mutableListOf<PublicKey>()

            // Keep searching until the stream is closed or timeout
            stream?.next()?.let { event ->
                if (event.event != null) {
                    results.add(event.event!!.author())
                }
            }

            return results
        } catch (e: Exception) {
            throw IllegalStateException("Failed to search nostr: ${e.message}", e)
        }
    }

    suspend fun verifyAddress(pubkey: PublicKey, address: String): Boolean {
        try {
            val address = Nip05Address.parse(address)
            val profile = profileFromAddress(HttpClient(), address)

            return profile.publicKey() == pubkey
        } catch (e: Exception) {
            throw IllegalStateException("Failed to verify address: ${e.message}", e)
        }
    }

    suspend fun verifyActivity(pubkey: PublicKey): Timestamp? {
        try {
            val filter = Filter().author(pubkey).limit(3u)
            val target = mutableMapOf<RelayUrl, List<Filter>>()
            NostrManager.BOOTSTRAP_RELAYS.forEach { relay ->
                target[RelayUrl.parse(relay)] = listOf(filter)
            }

            val events = client?.fetchEvents(
                target = ReqTarget.manual(target),
                timeout = Duration.parse("3s")
            )

            return events?.first()?.createdAt()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get latest activity: ${e.message}", e)
        }
    }
}
