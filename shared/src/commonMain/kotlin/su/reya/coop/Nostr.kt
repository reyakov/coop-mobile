package su.reya.coop

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.ReqExitPolicy
import rust.nostr.sdk.ReqTarget
import rust.nostr.sdk.SendEventTarget
import rust.nostr.sdk.SingleLetterTag
import rust.nostr.sdk.SleepWhenIdle
import rust.nostr.sdk.SubscribeAutoCloseOptions
import rust.nostr.sdk.Tag
import rust.nostr.sdk.TagKind
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnsignedEvent
import rust.nostr.sdk.UnwrappedGift
import rust.nostr.sdk.giftWrapAsync
import rust.nostr.sdk.initLogger
import rust.nostr.sdk.nip17ExtractRelayList
import kotlin.time.Duration

object NostrManager {
    val instance = Nostr()
}

class Nostr {
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    var client: Client? = null
        private set
    var signer: UniversalSigner = UniversalSigner(Keys.generate())
        private set
    var deviceSigner: AsyncNostrSigner? = null
        private set
    var sentEvents: MutableMap<EventId, List<RelayUrl>> = mutableMapOf()
        private set
    var rumorMap: MutableMap<EventId, EventId> = mutableMapOf()
        private set

    suspend fun init(dbPath: String) {
        try {
            if (_isInitialized.value) return

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
                    .gossipConfig(
                        GossipConfig()
                            .noBackgroundRefresh()
                            .fetchTimeout(Duration.parse("2s"))
                            .syncIdleTimeout(Duration.parse("100ms"))
                            .syncInitialTimeout(Duration.parse("100ms"))
                    )
                    .verifySubscriptions(false)
                    .automaticAuthentication(true)
                    .sleepWhenIdle(SleepWhenIdle.Enabled(idleTimeout))
                    .build()

            _isInitialized.value = true
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize Nostr client: ${e.message}", e)
        }
    }

    suspend fun waitUntilInitialized() {
        _isInitialized.first { it }
    }

    suspend fun connectBootstrapRelays() {
        // Bootstrap relays
        client?.addRelay(RelayUrl.parse("wss://relay.primal.net"))
        client?.addRelay(RelayUrl.parse("wss://user.kindpag.es"))
        client?.addRelay(RelayUrl.parse("wss://purplepag.es"))


        // Indexer relay for NIP-65 discovery
        client?.addRelay(
            url = RelayUrl.parse("wss://indexer.coracle.social"),
            capabilities = RelayCapabilities.gossip()
        )

        // Connect to all bootstrap relays and wait for all connections to be established
        client?.connect(Duration.parse("2s"))
    }

    suspend fun disconnect() {
        client?.shutdown()
    }

    suspend fun exit() {
        signer.switch(Keys.generate())
        deviceSigner = null
    }

    suspend fun setSigner(new: AsyncNostrSigner) {
        try {
            signer.switch(new)
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
                id = "all-gift-wraps"
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch user messages: ${e.message}", e)
        }
    }

    suspend fun handleLiteNotifications(
        onNewMessage: (UnsignedEvent) -> Unit,
    ) {
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
                            val subscriptionId = message.subscriptionId

                            // Ignore events not from the newest gift wraps subscription
                            if (subscriptionId != "newest-gift-wraps") continue

                            // Prevent processing duplicate events
                            if (processedEvent.contains(event.id())) continue
                            processedEvent.add(event.id())

                            if (event.kind().asStd()?.equals(KindStandard.GIFT_WRAP) == true) {
                                try {
                                    val rumor = extractRumor(event)

                                    // Handle new message
                                    rumor?.createdAt()?.asSecs()?.let {
                                        if (it >= now.asSecs()) {
                                            onNewMessage(rumor)
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("Failed to extract rumor: $e")
                                }
                            }
                        }

                        else -> {
                            /* Ignore other event kinds */
                        }
                    }
                }

                else -> {
                    /* Ignore other message types */
                }
            }
        }
    }

    suspend fun handleNotifications(
        onMetadataUpdate: (PublicKey, Metadata) -> Unit,
        onContactListUpdate: (List<PublicKey>) -> Unit,
        onNewMessage: (UnsignedEvent) -> Unit,
        onSubscriptionClose: () -> Unit,
    ) = coroutineScope {
        val now = Timestamp.now()
        val processedEvent = mutableSetOf<EventId>()
        val notifications = client?.notifications() ?: return@coroutineScope

        var eoseTrackerJob: Job? = null

        while (true) {
            val notification = notifications.next() ?: continue

            when (notification) {
                is ClientNotification.Message -> {
                    val relayUrl = notification.relayUrl

                    when (val message = notification.message.asEnum()) {
                        is RelayMessageEnum.EventMsg -> {
                            val event = message.event
                            val id = message.subscriptionId

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
                                try {
                                    val rumor = extractRumor(event)

                                    // Logic to notify UI after processing
                                    // Cancel previous tracker if it exists
                                    eoseTrackerJob?.cancel()
                                    // Start a new tracker
                                    eoseTrackerJob = launch {
                                        delay(10000) // Wait for 10 seconds
                                        onSubscriptionClose()
                                    }

                                    // Handle new message
                                    rumor?.createdAt()?.asSecs()?.let {
                                        if (it >= now.asSecs()) {
                                            onNewMessage(rumor)
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("Failed to extract rumor: $e")
                                }
                            }
                        }

                        is RelayMessageEnum.EndOfStoredEvents -> {
                            val subscriptionId = message.subscriptionId

                            if (subscriptionId == "messages") {
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
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get cached rumor: ${e.message}", e)
        }
    }

    private suspend fun setCachedRumor(giftId: EventId, rumor: UnsignedEvent) {
        try {
            val currentUser =
                signer.currentUser ?: throw IllegalStateException("User not signed in")

            // Ensure the rumor ID is set
            val rumor = rumor.ensureId()
            val roomId = rumor.roomId()

            // Construct reference tags
            val tags = listOf(
                Tag.identifier(giftId.toHex()),
                Tag.event(rumor.id()!!),
                Tag.reference(roomId.toString()),
                Tag.custom(TagKind.Unknown("k"), listOf("dm"))
            )

            // Set event kind
            val kind = Kind.fromStd(KindStandard.APPLICATION_SPECIFIC_DATA);

            val event = EventBuilder(kind, rumor.asJson())
                .tags(tags)
                .build(currentUser)
                .signWithKeys(Keys.generate())

            client?.database()?.saveEvent(event)
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

    suspend fun createIdentity(keys: Keys, name: String, bio: String?, picture: String?) {
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
            Metadata.fromRecord(MetadataRecord(displayName = name, about = bio, picture = picture))
        val metadataEvent = EventBuilder.metadata(metadata).signWithKeys(keys)

        client?.sendEvent(
            event = metadataEvent,
            target = SendEventTarget.broadcast(),
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

    suspend fun getAllCacheMetadata(): Map<PublicKey, Metadata> {
        try {
            val filter = Filter().kind(Kind.fromStd(KindStandard.METADATA)).limit(200u)
            val events = client?.database()?.query(filter)
            val results = mutableMapOf<PublicKey, Metadata>()

            events?.toVec()?.forEach { event ->
                val metadata = Metadata.fromJson(event.content())
                results[event.author()] = metadata
            }

            return results
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get cache metadata: ${e.message}", e)
        }
    }

    suspend fun fetchMetadataBatch(keys: List<PublicKey>) {
        try {
            val limit = keys.size.toULong() * 4u;
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
                        RelayUrl.parse("wss://user.kindpag.es") to listOf(filter),
                        RelayUrl.parse("wss://relay.primal.net") to listOf(filter),
                        RelayUrl.parse("wss://relay.damus.io") to listOf(filter),
                    )
                )

            client?.subscribe(target = target, closeOn = opts)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch metadata batch: ${e.message}", e)
        }
    }

    suspend fun getChatRooms(): Set<Room>? {
        try {
            val userPubkey = signer.currentUser ?: throw IllegalStateException("User not signed in")
            val kind = Kind.fromStd(KindStandard.APPLICATION_SPECIFIC_DATA)
            val kTag = SingleLetterTag.lowercase(Alphabet.K)

            // Get all events sent by the user
            val filter = Filter().kind(kind).author(userPubkey).customTag(kTag, "dm")
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
                        val filter =
                            Filter().kind(kind).author(userPubkey).pubkeys(newRoom.members.toList())

                        // Determine if it's an ongoing room
                        val isOngoing = client?.database()?.query(filter)?.isEmpty() == false

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
                ?.sortedByDescending { it.createdAt().asSecs() } ?: emptyList()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get chat room messages: ${e.message}", e)
        }
    }

    suspend fun chatRoomConnect(members: List<PublicKey>): Map<PublicKey, List<RelayUrl>> {
        try {
            val results = mutableMapOf<PublicKey, MutableList<RelayUrl>>()

            members.forEach { member ->
                results[member] = mutableListOf<RelayUrl>()
                val kind = Kind.fromStd(KindStandard.INBOX_RELAYS)
                val filter = Filter().kind(kind).author(member).limit(1u)

                val stream = client?.streamEvents(
                    target = ReqTarget.auto(listOf(filter)),
                    id = "room-${member.toBech32().substring(0, 10)}",
                    timeout = Duration.parse("3s"),
                    policy = ReqExitPolicy.ExitOnEose
                )

                stream?.next()?.let { res ->
                    if (res.event != null) {
                        // Connect to the msg relays
                        connectMsgRelays(res.event!!)
                        // Mark the member as connected
                        results[member]?.add(res.relayUrl)
                    }
                }
            }

            return results
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch relays: ${e.message}", e)
        }
    }

    suspend fun connectMsgRelays(event: Event) {
        try {
            val urls = nip17ExtractRelayList(event);
            for (url in urls) {
                if (client?.relay(url) == null) {
                    client?.addRelay(url)
                    client?.connectRelay(url)
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to connect to relays: ${e.message}", e)
        }
    }

    suspend fun sendMessage(
        to: List<PublicKey>,
        content: String,
        subject: String? = null,
        replies: List<EventId> = emptyList(),
        onRumorCreated: ((UnsignedEvent) -> Unit)? = null,
    ) {
        try {
            val currentUser =
                signer.currentUser ?: throw IllegalStateException("User not signed in")

            val tags = mutableListOf<Tag>()

            // Add a subject tag if provided
            if (subject != null) {
                tags.add(Tag.custom(TagKind.Subject, listOf(subject)))
            }

            // Add event tags for replies
            if (replies.isNotEmpty()) {
                replies.forEach { replyId ->
                    tags.add(Tag.event(replyId))
                }
            }

            // Add public key tags for each recipient
            to.forEach { pubkey ->
                if (pubkey != currentUser) {
                    tags.add(Tag.publicKey(pubkey))
                }
            }

            for (receiver in listOf(currentUser) + to) {
                // Construct the rumor event
                // NEVER SIGN this event with the current user signer
                val rumor = EventBuilder
                    .privateMsgRumor(receiver = receiver, message = content)
                    .tags(tags)
                    .build(currentUser)
                    // Ensure the event ID is set
                    .ensureId()

                // Emit the rumor to the chat screen
                if (receiver == currentUser) {
                    onRumorCreated?.invoke(rumor)
                }

                // Construct the gift wrap event
                val gift = giftWrapAsync(
                    signer = signer,
                    receiverPubkey = receiver,
                    rumor = rumor,
                    extraTags = listOf(
                        Tag.custom(TagKind.Unknown("k"), listOf("14"))
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
            val target =
                ReqTarget.manual(mapOf(RelayUrl.parse("wss://antiprimal.net") to listOf(filter)))

            val stream = client?.streamEvents(
                target = target,
                id = "search",
                timeout = Duration.parse("4s"),
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
}
